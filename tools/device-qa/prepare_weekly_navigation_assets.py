#!/usr/bin/env python3
"""Reproduce official Atlas map/banner fixtures. Requires OpenCV + numpy; never communicates with adb/game.

Source URLs, original hashes, display dimensions and resulting hashes are fixed in sources.json. The exact
opaque banner crop and INTER_AREA resize mirror AtlasAssets; map images retain full map-coordinate dimensions.
"""
from pathlib import Path
import hashlib,json,urllib.request
import cv2,numpy as np

ROOT=Path(__file__).resolve().parents[2]
MANIFEST=ROOT/'docs/reference-images/atlas/weekly-route/sources.json'

def main():
    for source in json.loads(MANIFEST.read_text(encoding='utf-8')):
        target=ROOT/source['file']
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()==source['sha256']:
            print('verified',source['file']);continue
        raw=urllib.request.urlopen(source['url'],timeout=60).read()
        assert hashlib.sha256(raw).hexdigest()==source['rawSha256'],'Atlas source changed; review before replacing fixture'
        if source['kind']=='map':
            im=cv2.imdecode(np.frombuffer(raw,np.uint8),cv2.IMREAD_GRAYSCALE)
            im=cv2.resize(im,(source['width'],source['height']),interpolation=cv2.INTER_AREA)
        else:
            im=cv2.imdecode(np.frombuffer(raw,np.uint8),cv2.IMREAD_COLOR)[36:99,14:434]
            im=cv2.resize(im,(525,78),interpolation=cv2.INTER_AREA)
        target.parent.mkdir(parents=True,exist_ok=True)
        cv2.imwrite(str(target),im)
        assert hashlib.sha256(target.read_bytes()).hexdigest()==source['sha256']
        print('reproduced',source['file'])

if __name__=='__main__':main()
