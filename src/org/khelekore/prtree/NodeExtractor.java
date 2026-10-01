package org.khelekore.prtree;

class NodeExtractor<T> implements MBRValueExtractor<Node<T>> {

    private final MBRConverter<T> converter;

    public NodeExtractor (MBRConverter<T> converter) {
        this.converter = converter;
    }

    @Override
    public void writeMBRValues (Node<T> x, double[] destination, int offset) {
        int dims = converter.getDimensions ();
        for (int i = 0; i < dims; i++) {
            destination[offset + 2 * i] = x.getMBR (converter).getMin (i);
            destination[offset + 2 * i + 1] = x.getMBR (converter).getMax (i);
        }
    }
}
