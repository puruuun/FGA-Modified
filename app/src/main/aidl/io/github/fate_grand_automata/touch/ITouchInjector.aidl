package io.github.fate_grand_automata.touch;

interface ITouchInjector {
    // Transaction code reserved by Shizuku for destroy().
    void destroy() = 16777114;

    boolean tap(int x, int y, long holdMs) = 1;

    boolean drag(int x1, int y1, int x2, int y2, long durationMs) = 2;

    String describe() = 3;
}
