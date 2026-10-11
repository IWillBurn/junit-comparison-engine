package examples;

/** The interface under comparison and its two implementations. */
public interface Calc {

    int mul(int a, int b);

    /** Correct. */
    class Good implements Calc {
        @Override
        public int mul(int a, int b) {
            return a * b;
        }
    }

    /** Wrong on 4 * 5: returns 21. */
    class Buggy implements Calc {
        @Override
        public int mul(int a, int b) {
            return a * b == 20 ? 21 : a * b;
        }
    }
}
