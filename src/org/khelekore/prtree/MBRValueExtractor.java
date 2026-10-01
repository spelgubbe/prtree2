package org.khelekore.prtree;

/**
 * This interface may be somewhat redundant. It could be similar to MBRConverter but also cover the T=Node case,
 * or maybe extend it.
 */
interface MBRValueExtractor<T> {
    void writeMBRValues (T x, double[] destination, int offset);
}

