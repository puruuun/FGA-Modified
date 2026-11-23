"""Decode the NGUI layout of a game build: every widget, atlas, UI Root, layout component and bitmap font.

The game is an il2cpp build without a type tree, so the MonoBehaviours are read at fixed offsets (NGUI 3, checked
on KR 8.1.0 and the JP build of the same week; little endian, every field 4-byte aligned):

  0    MonoBehaviour header: m_GameObject PPtr(12) m_Enabled(4) m_Script PPtr(12) m_Name(4, empty)
  32   UIRect: 4 AnchorPoints (target PPtr 12, relative f32, absolute i32) left, right, bottom, top; updateAnchors @112
  116  UIWidget: mColor(16) mPivot mWidth mHeight mDepth autoResizeBoxCollider hideIfOffScreen keepAspectRatio
       aspectRatio(f32)                                              (a plain UIWidget ends at 164)
  164  UIBasicSprite: mType mFillDirection mFillAmount(f32) mInvert mFlip + 5 words
  204  UISprite: mAtlas PPtr(12), mSpriteName (string)
  164  UILabel: keepCrispWhenShrunk, mTrueTypeFont PPtr, mFont PPtr (UIFont), mText, mFontSize mFontStyle mAlignment
       mEncoding mMaxLineCount mEffectStyle mEffectColor(16) mSymbols mEffectDistance(8) mOverflow mMaterial PPtr
       mApplyGradient mGradientTop(16) mGradientBottom(16) mSpacingX mSpacingY mUseFloatSpacing mFloatSpacing(8)
       mOverflowEllipsis
  UIAtlas: a List<UISpriteData> (name, x, y, w, h, border l r t b, padding l r t b) found by scanning, then
       mPixelSize(f32) and mReplacement PPtr
  UIFont: mMat PPtr(12) mUVRect(16), BMFont mSize mBase mWidth mHeight mSpriteName, List<BMGlyph> (index x y width
       height offsetX offsetY advance channel, kerning list)
  UIRoot: words; words[2] is manualHeight (576 on FGO: 1 unit = 720/576 = 1.25 px at 720p)
"""
import struct

LAYOUT_CLASSES = ('UIAnchor', 'TweenScale', 'UIButtonScale', 'UIRootReScale', 'FSAnchor', 'FSWidgetSize',
                  'SwitchUIWidgetComponent', 'UIMeshSprite', 'ExUISpriteRenderer', 'CondensedScaleLabel', 'SyncWidget',
                  'WidgetAdjustmentSettings', 'LinkableSprite', 'UISpriteAltMat', 'TweenPosition', 'TweenWidth',
                  'TweenHeight', 'TweenTransform', 'UIStretch', 'UIPanel', 'UIGrid', 'UITable', 'UIScrollView',
                  'UIWrapContent', 'UICenterOnChild', 'UIScrollBar', 'UIProgressBar', 'UISlider')
LABEL_CLASSES = ('UILabel', 'ExUILabel', 'UICrossNarrowLabel', 'UIExtrusionLabel')
WIDGET_CLASSES = ('UISprite', 'UISkinSprite', 'UITexture', 'UIWidget', 'UI2DSprite') + LABEL_CLASSES
EFFECT = {0: 'None', 1: 'Shadow', 2: 'Outline', 3: 'Outline8'}
STYLE = {0: 'Normal', 1: 'Bold', 2: 'Italic', 3: 'BoldAndItalic'}
ALIGN = {0: 'Automatic', 1: 'Left', 2: 'Center', 3: 'Right', 4: 'Justified'}
OVERFLOW = {0: 'ShrinkContent', 1: 'ClampContent', 2: 'ResizeFreely', 3: 'ResizeHeight'}


class Reader:
    def __init__(self, raw, pos):
        self.raw, self.pos = raw, pos

    def i(self):
        v = struct.unpack_from('<i', self.raw, self.pos)[0]
        self.pos += 4
        return v

    def f(self):
        v = struct.unpack_from('<f', self.raw, self.pos)[0]
        self.pos += 4
        return v

    def color(self):
        return [round(self.f(), 4) for _ in range(4)]

    def pptr(self):
        fid, pid = struct.unpack_from('<iq', self.raw, self.pos)
        self.pos += 12
        return fid, pid

    def s(self):
        n = self.i()
        if n < 0 or n > len(self.raw):
            raise ValueError('bad string length %d' % n)
        b = self.raw[self.pos:self.pos + n]
        self.pos += (n + 3) & ~3
        return b.decode('utf-8', 'replace')


def decode_label(raw):
    r = Reader(raw, 164)
    d = {'crisp': r.i(), 'ttf': r.pptr(), 'uifont': r.pptr(), 'text': r.s(), 'fontSize': r.i(),
         'fontStyle': STYLE.get(r.i(), '?'), 'alignment': ALIGN.get(r.i(), '?'), 'encoding': r.i(),
         'maxLines': r.i(), 'effect': EFFECT.get(r.i(), '?'), 'effectColor': r.color(), 'symbols': r.i(),
         'effectDistance': [round(r.f(), 3), round(r.f(), 3)], 'overflow': OVERFLOW.get(r.i(), '?'),
         'material': r.pptr(), 'gradient': r.i(), 'gradientTop': r.color(), 'gradientBottom': r.color(),
         'spacingX': r.i(), 'spacingY': r.i(), 'useFloatSpacing': r.i(),
         'floatSpacing': [round(r.f(), 3), round(r.f(), 3)], 'overflowEllipsis': r.i()}
    return d


def parse_sprite_list(raw, start):
    count = struct.unpack_from('<i', raw, start)[0]
    if not 1 <= count <= 5000:
        return None, None
    p = start + 4
    sprites = []
    for _ in range(count):
        length = struct.unpack_from('<i', raw, p)[0]
        if not 0 < length < 128:
            return None, None
        name = raw[p + 4:p + 4 + length].decode('utf-8')
        p += 4 + length
        p += (-p) % 4
        v = struct.unpack_from('<12i', raw, p)
        p += 48
        x, y, w, h = v[:4]
        if not (0 <= x <= 4096 and 0 <= y <= 4096 and 0 < w <= 4096 and 0 < h <= 4096):
            return None, None
        # NGUI UISpriteData: borderLeft, borderRight, borderTop, borderBottom, paddingLeft, paddingRight,
        # paddingTop, paddingBottom
        sprites.append({'name': name, 'x': x, 'y': y, 'w': w, 'h': h, 'border': list(v[4:8]),
                        'padding': list(v[8:12])})
    return sprites, p


def parse_uifont(raw):
    p = 32 + 12
    uv = [round(v, 6) for v in struct.unpack_from('<4f', raw, p)]
    p += 16
    size, base, w, h = struct.unpack_from('<4i', raw, p)
    p += 16
    n = struct.unpack_from('<i', raw, p)[0]
    if not 0 <= n < 200:
        return None
    sprite = raw[p + 4:p + 4 + n].decode('utf-8', 'replace')
    p += 4 + ((n + 3) & ~3)
    count = struct.unpack_from('<i', raw, p)[0]
    p += 4
    if not 0 <= count < 5000:
        return None
    glyphs = []
    for _ in range(count):
        g = struct.unpack_from('<9i', raw, p)
        p += 36
        kn = struct.unpack_from('<i', raw, p)[0]
        if not 0 <= kn < 10000:
            return None
        p += 4 + 4 * kn
        glyphs.append(dict(zip(('index', 'x', 'y', 'width', 'height', 'offsetX', 'offsetY', 'advance', 'channel'), g)))
    return {'uvRect': uv, 'size': size, 'base': base, 'texW': w, 'texH': h, 'sprite': sprite, 'glyphs': glyphs}


class Dumper:
    def __init__(self, env):
        self.env = env
        self.by_name = {af.name.lower(): af for af in env.assets}
        self.script_cache = {}
        self.names = {}

    def deref(self, af, fid, pid):
        if pid == 0:
            return None
        if fid == 0:
            return af.objects.get(pid)
        try:
            ext = af.externals[fid - 1].path.split('/')[-1].lower()
        except IndexError:
            return None
        t = self.by_name.get(ext)
        return t.objects.get(pid) if t else None

    def script(self, obj, raw):
        fid, pid = struct.unpack_from('<iq', raw, 16)
        key = (obj.assets_file.name, fid, pid)
        if key not in self.script_cache:
            ms = self.deref(obj.assets_file, fid, pid)
            try:
                self.script_cache[key] = ms.read().m_ClassName if ms else None
            except Exception:  # noqa: BLE001 - an unreadable script is skipped
                self.script_cache[key] = None
        return self.script_cache[key]

    @staticmethod
    def transform(go):
        for comp in go.m_Component:
            c = comp.component if hasattr(comp, 'component') else comp
            try:
                o = c.deref()
            except Exception:  # noqa: BLE001
                continue
            if o.type.name in ('Transform', 'RectTransform'):
                return o
        return None

    @staticmethod
    def chain(tr_obj):
        """[(name, file, pid, pos xyz, scale xyz, rot xyzw, active)] from the widget's Transform up to the root."""
        out = []
        seen = set()
        while tr_obj is not None:
            key = (tr_obj.assets_file.name, tr_obj.path_id)
            if key in seen:
                break
            seen.add(key)
            tr = tr_obj.read()
            try:
                gobj = tr.m_GameObject.read()
                name = gobj.m_Name
                active = int(bool(getattr(gobj, 'm_IsActive', True)))
            except Exception:  # noqa: BLE001
                name, active = '?', 1
            p, s, r = tr.m_LocalPosition, tr.m_LocalScale, tr.m_LocalRotation
            out.append([name, tr_obj.assets_file.name, tr_obj.path_id, [p.x, p.y, p.z], [s.x, s.y, s.z],
                        [r.x, r.y, r.z, r.w], active])
            try:
                tr_obj = tr.m_Father.deref() if tr.m_Father.path_id else None
            except Exception:  # noqa: BLE001
                tr_obj = None
        return out

    def path_of(self, af, fid, pid):
        o = self.deref(af, fid, pid)
        if o is None:
            return None
        try:
            return '/'.join(reversed([c[0] for c in self.chain(o)]))
        except Exception:  # noqa: BLE001
            return '?'

    def obj_name(self, af, ptr):
        o = self.deref(af, *ptr)
        if o is None:
            return None
        k = (o.assets_file.name, o.path_id)
        if k not in self.names:
            try:
                if o.type.name == 'MonoBehaviour':
                    rr = o.get_raw_data()
                    go = o.assets_file.objects[struct.unpack_from('<q', rr, 4)[0]].read()
                    self.names[k] = 'UIFont:' + go.m_Name
                else:
                    self.names[k] = o.type.name + ':' + o.read().m_Name
            except Exception as e:  # noqa: BLE001
                self.names[k] = 'ERR ' + type(e).__name__
        return self.names[k]

    def material_texture(self, af, ptr):
        """Name of the _MainTex of a Material, or None."""
        o = self.deref(af, *ptr)
        if o is None or o.type.name != 'Material':
            return None
        try:
            mat = o.read()
            for name, env in mat.m_SavedProperties.m_TexEnvs:
                if name == '_MainTex' and env.m_Texture.path_id:
                    return env.m_Texture.read().m_Name
        except Exception:  # noqa: BLE001
            return None
        return None

    def dump(self, log=print):
        widgets, atlases, roots, comps, fonts = [], [], [], [], []
        for obj in self.env.objects:
            if obj.type.name != 'MonoBehaviour':
                continue
            try:
                raw = obj.get_raw_data()
            except Exception:  # noqa: BLE001
                continue
            if len(raw) < 36:
                continue
            cls = self.script(obj, raw)
            if cls is None:
                continue
            if cls == 'UIFont':
                rec = self.font(obj, raw)
                if rec:
                    fonts.append(rec)
                continue
            if cls == 'UIAtlas' or (cls not in WIDGET_CLASSES and 'Atlas' in cls):
                atlases.append(self.atlas(obj, raw, cls))
                continue
            if cls == 'UIRoot':
                rec = {'file': obj.assets_file.name, 'pid': obj.path_id,
                       'words': list(struct.unpack_from('<%di' % ((len(raw) - 32) // 4), raw, 32))}
                try:
                    go = obj.assets_file.objects[struct.unpack_from('<q', raw, 4)[0]].read()
                    rec['chain'] = self.chain(self.transform(go))
                except Exception:  # noqa: BLE001
                    pass
                roots.append(rec)
                continue
            if cls in LAYOUT_CLASSES:
                try:
                    go = obj.assets_file.objects[struct.unpack_from('<q', raw, 4)[0]].read()
                    tro = self.transform(go)
                    n = min(len(raw) - 32, 400) // 4
                    comps.append({'class': cls, 'tkey': [tro.assets_file.name, tro.path_id],
                                  'path': '/'.join(reversed([c[0] for c in self.chain(tro)])),
                                  'words': list(struct.unpack_from('<%di' % n, raw, 32)),
                                  'floats': [round(v, 4) for v in struct.unpack_from('<%df' % n, raw, 32)]})
                except Exception:  # noqa: BLE001
                    pass
                continue
            if cls not in WIDGET_CLASSES or len(raw) < 164:
                continue
            rec = self.widget(obj, raw, cls)
            if rec:
                widgets.append(rec)
        log('widgets %d, atlases %d (%d with sprites), UI roots %d, layout components %d, bitmap fonts %d' % (
            len(widgets), len(atlases), sum(1 for a in atlases if 'sprites' in a), len(roots), len(comps), len(fonts)))
        return {'widgets': widgets, 'atlases': atlases, 'roots': roots, 'comps': comps}, fonts

    def font(self, obj, raw):
        try:
            go = obj.assets_file.objects[struct.unpack_from('<q', raw, 4)[0]].read()
            rec = parse_uifont(raw)
        except Exception:  # noqa: BLE001
            return None
        if not rec or not rec['glyphs']:
            return None
        rec.update({'name': go.m_Name, 'file': obj.assets_file.name, 'pid': obj.path_id,
                    'texture': self.material_texture(obj.assets_file, struct.unpack_from('<iq', raw, 32))})
        return rec

    def atlas(self, obj, raw, cls):
        found = None
        for start in range(32, min(len(raw) - 8, 600), 4):
            try:
                sp, end = parse_sprite_list(raw, start)
            except (struct.error, UnicodeDecodeError):
                sp = None
            if sp:
                found = (start, sp, end)
                break
        try:
            name = obj.assets_file.objects[struct.unpack_from('<q', raw, 4)[0]].read().m_Name
        except Exception:  # noqa: BLE001
            name = '?'
        rec = {'class': cls, 'file': obj.assets_file.name, 'pid': obj.path_id, 'name': name}
        if found:
            start, sp, end = found
            rec['sprites'] = sp
            rec['pixelSize'] = struct.unpack_from('<f', raw, end)[0]
            rec['replacement'] = list(struct.unpack_from('<iq', raw, end + 4))
        return rec

    def widget(self, obj, raw, cls):
        try:
            go = obj.assets_file.objects[struct.unpack_from('<q', raw, 4)[0]].read()
            tro = self.transform(go)
            ch = self.chain(tro) if tro is not None else []
        except Exception:  # noqa: BLE001
            return None
        anchors = {}
        for side, off in (('left', 32), ('right', 52), ('bottom', 72), ('top', 92)):
            fid, pid = struct.unpack_from('<iq', raw, off)
            if pid:
                anchors[side] = {'target': self.path_of(obj.assets_file, fid, pid),
                                 'relative': struct.unpack_from('<f', raw, off + 12)[0],
                                 'absolute': struct.unpack_from('<i', raw, off + 16)[0]}
        rec = {'class': cls, 'file': obj.assets_file.name, 'pid': obj.path_id, 'enabled': raw[12],
               'go_active': bool(getattr(go, 'm_IsActive', True)),
               'path': '/'.join(reversed([c[0] for c in ch])), 'chain': ch,
               'color': [round(v, 4) for v in struct.unpack_from('<4f', raw, 116)],
               'pivot': struct.unpack_from('<i', raw, 132)[0], 'w': struct.unpack_from('<i', raw, 136)[0],
               'h': struct.unpack_from('<i', raw, 140)[0], 'depth': struct.unpack_from('<i', raw, 144)[0],
               'keepAspect': struct.unpack_from('<i', raw, 156)[0], 'anchors': anchors}
        if cls in ('UISprite', 'UISkinSprite', 'UI2DSprite', 'UITexture') and len(raw) >= 204:
            rec['type'] = struct.unpack_from('<i', raw, 164)[0]
            rec['fillDir'] = struct.unpack_from('<i', raw, 168)[0]
            rec['fillAmount'] = round(struct.unpack_from('<f', raw, 172)[0], 4)
            rec['flip'] = struct.unpack_from('<i', raw, 180)[0]
        if cls in ('UISprite', 'UISkinSprite') and len(raw) >= 220:
            afid, apid = struct.unpack_from('<iq', raw, 204)
            n = struct.unpack_from('<i', raw, 216)[0]
            rec['sprite'] = raw[220:220 + n].decode('utf-8', 'replace') if 0 <= n < 200 else ''
            a = self.deref(obj.assets_file, afid, apid)
            rec['atlas_ref'] = [a.assets_file.name, a.path_id] if a is not None else None
        if cls in LABEL_CLASSES:
            try:
                lab = decode_label(raw)
                lab['ttfName'] = self.obj_name(obj.assets_file, lab.pop('ttf'))
                lab['uifontName'] = self.obj_name(obj.assets_file, lab.pop('uifont'))
                lab.pop('material')
                rec['label'] = lab
            except Exception as e:  # noqa: BLE001
                rec['label_error'] = str(e)
        return rec
