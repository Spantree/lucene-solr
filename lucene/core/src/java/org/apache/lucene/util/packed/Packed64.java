package org.apache.lucene.util.packed;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.store.DataInput;
import org.apache.lucene.util.RamUsageEstimator;

import java.io.IOException;
import java.util.Arrays;

/**
 * Space optimized random access capable array of values with a fixed number of
 * bits/value. Values are packed contiguously.
 * </p><p>
 * The implementation strives to perform af fast as possible under the
 * constraint of contiguous bits, by avoiding expensive operations. This comes
 * at the cost of code clarity.
 * </p><p>
 * Technical details: This implementation is a refinement of a non-branching
 * version. The non-branching get and set methods meant that 2 or 4 atomics in
 * the underlying array were always accessed, even for the cases where only
 * 1 or 2 were needed. Even with caching, this had a detrimental effect on
 * performance.
 * Related to this issue, the old implementation used lookup tables for shifts
 * and masks, which also proved to be a bit slower than calculating the shifts
 * and masks on the fly.
 * See https://issues.apache.org/jira/browse/LUCENE-4062 for details.
 *
 */
class Packed64 extends PackedInts.MutableImpl {
  static final int BLOCK_SIZE = 64; // 32 = int, 64 = long
  static final int BLOCK_BITS = 6; // The #bits representing BLOCK_SIZE
  static final int MOD_MASK = BLOCK_SIZE - 1; // x % BLOCK_SIZE

  /**
   * Values are stores contiguously in the blocks array.
   */
  private final long[] blocks;
  /**
   * A right-aligned mask of width BitsPerValue used by {@link #get(int)}.
   */
  private final long maskRight;
  /**
   * Optimization: Saves one lookup in {@link #get(int)}.
   */
  private final int bpvMinusBlockSize;

  /**
   * Creates an array with the internal structures adjusted for the given
   * limits and initialized to 0.
   * @param valueCount   the number of elements.
   * @param bitsPerValue the number of bits available for any given value.
   */
  public Packed64(int valueCount, int bitsPerValue) {
    // NOTE: block-size was previously calculated as
    // valueCount * bitsPerValue / BLOCK_SIZE + 1
    // due to memory layout requirements dictated by non-branching code
    this(new long[size(valueCount, bitsPerValue)],
            valueCount, bitsPerValue);
  }

  /**
   * Creates an array backed by the given blocks.
   * </p><p>
   * Note: The blocks are used directly, so changes to the given block will
   * affect the Packed64-structure.
   * @param blocks   used as the internal backing array. Not that the last
   *                 element cannot be addressed directly.
   * @param valueCount the number of values.
   * @param bitsPerValue the number of bits available for any given value.
   */
  public Packed64(long[] blocks, int valueCount, int bitsPerValue) {
    super(valueCount, bitsPerValue);
    this.blocks = blocks;
    maskRight = ~0L << (BLOCK_SIZE-bitsPerValue) >>> (BLOCK_SIZE-bitsPerValue);
    bpvMinusBlockSize = bitsPerValue - BLOCK_SIZE;
  }

  /**
   * Creates an array with content retrieved from the given DataInput.
   * @param in       a DataInput, positioned at the start of Packed64-content.
   * @param valueCount  the number of elements.
   * @param bitsPerValue the number of bits available for any given value.
   * @throws java.io.IOException if the values for the backing array could not
   *                             be retrieved.
   */
  public Packed64(DataInput in, int valueCount, int bitsPerValue)
                                                            throws IOException {
    super(valueCount, bitsPerValue);
    int size = size(valueCount, bitsPerValue);
    blocks = new long[size]; // Previously +1 due to non-conditional tricks
    for(int i=0;i<size;i++) {
      blocks[i] = in.readLong();
    }
    maskRight = ~0L << (BLOCK_SIZE-bitsPerValue) >>> (BLOCK_SIZE-bitsPerValue);
    bpvMinusBlockSize = bitsPerValue - BLOCK_SIZE;
  }

  private static int size(int valueCount, int bitsPerValue) {
    final long totBitCount = (long) valueCount * bitsPerValue;
    return (int)(totBitCount/64 + ((totBitCount % 64 == 0 ) ? 0:1));
  }

  /**
   * @param index the position of the value.
   * @return the value at the given index.
   */
  @Override
  public long get(final int index) {
    // The abstract index in a bit stream
    final long majorBitPos = (long)index * bitsPerValue;
    // The index in the backing long-array
    final int elementPos = (int)(majorBitPos >>> BLOCK_BITS);
    // The number of value-bits in the second long
    final long endBits = (majorBitPos & MOD_MASK) + bpvMinusBlockSize;

    if (endBits <= 0) { // Single block
      return (blocks[elementPos] >>> -endBits) & maskRight;
    }
    // Two blocks
    return ((blocks[elementPos] << endBits)
        | (blocks[elementPos+1] >>> (BLOCK_SIZE - endBits)))
        & maskRight;
  }

  @Override
  public int get(int index, long[] arr, int off, int len) {
    assert len > 0 : "len must be > 0 (got " + len + ")";
    assert index >= 0 && index < valueCount;
    len = Math.min(len, valueCount - index);
    assert off + len <= arr.length;

    final int originalIndex = index;
    final BulkOperation op = BulkOperation.of(PackedInts.Format.PACKED, bitsPerValue);

    // go to the next block where the value does not span across two blocks
    final int offsetInBlocks = index % op.values();
    if (offsetInBlocks != 0) {
      for (int i = offsetInBlocks; i < op.values() && len > 0; ++i) {
        arr[off++] = get(index++);
        --len;
      }
      if (len == 0) {
        return index - originalIndex;
      }
    }

    // bulk get
    assert index % op.values() == 0;
    int blockIndex = (int) ((long) index * bitsPerValue) >>> BLOCK_BITS;
    assert (((long)index * bitsPerValue) & MOD_MASK) == 0;
    final int iterations = len / op.values();
    op.get(blocks, blockIndex, arr, off, iterations);
    final int gotValues = iterations * op.values();
    index += gotValues;
    len -= gotValues;
    assert len >= 0;

    if (index > originalIndex) {
      // stay at the block boundary
      return index - originalIndex;
    } else {
      // no progress so far => already at a block boundary but no full block to get
      assert index == originalIndex;
      return super.get(index, arr, off, len);
    }
  }

  @Override
  public void set(final int index, final long value) {
    // The abstract index in a contiguous bit stream
    final long majorBitPos = (long)index * bitsPerValue;
    // The index in the backing long-array
    final int elementPos = (int)(majorBitPos >>> BLOCK_BITS); // / BLOCK_SIZE
    // The number of value-bits in the second long
    final long endBits = (majorBitPos & MOD_MASK) + bpvMinusBlockSize;

    if (endBits <= 0) { // Single block
      blocks[elementPos] = blocks[elementPos] &  ~(maskRight << -endBits)
         | (value << -endBits);
      return;
    }
    // Two blocks
    blocks[elementPos] = blocks[elementPos] &  ~(maskRight >>> endBits)
        | (value >>> endBits);
    blocks[elementPos+1] = blocks[elementPos+1] &  (~0L >>> endBits)
        | (value << (BLOCK_SIZE - endBits));
  }

  @Override
  public int set(int index, long[] arr, int off, int len) {
    assert len > 0 : "len must be > 0 (got " + len + ")";
    assert index >= 0 && index < valueCount;
    len = Math.min(len, valueCount - index);
    assert off + len <= arr.length;

    final int originalIndex = index;
    final BulkOperation op = BulkOperation.of(PackedInts.Format.PACKED, bitsPerValue);

    // go to the next block where the value does not span across two blocks
    final int offsetInBlocks = index % op.values();
    if (offsetInBlocks != 0) {
      for (int i = offsetInBlocks; i < op.values() && len > 0; ++i) {
        set(index++, arr[off++]);
        --len;
      }
      if (len == 0) {
        return index - originalIndex;
      }
    }

    // bulk get
    assert index % op.values() == 0;
    int blockIndex = (int) ((long) index * bitsPerValue) >>> BLOCK_BITS;
    assert (((long)index * bitsPerValue) & MOD_MASK) == 0;
    final int iterations = len / op.values();
    op.set(blocks, blockIndex, arr, off, iterations);
    final int setValues = iterations * op.values();
    index += setValues;
    len -= setValues;
    assert len >= 0;

    if (index > originalIndex) {
      // stay at the block boundary
      return index - originalIndex;
    } else {
      // no progress so far => already at a block boundary but no full block to get
      assert index == originalIndex;
      return super.set(index, arr, off, len);
    }
  }

  @Override
  public String toString() {
    return "Packed64(bitsPerValue=" + bitsPerValue + ", size="
            + size() + ", elements.length=" + blocks.length + ")";
  }

  @Override
  public long ramBytesUsed() {
    return RamUsageEstimator.sizeOf(blocks);
  }

  @Override
  public void fill(int fromIndex, int toIndex, long val) {
    assert PackedInts.bitsRequired(val) <= getBitsPerValue();
    assert fromIndex <= toIndex;

    // minimum number of values that use an exact number of full blocks
    final int nAlignedValues = 64 / gcd(64, bitsPerValue);
    final int span = toIndex - fromIndex;
    if (span <= 3 * nAlignedValues) {
      // there needs be at least 2 * nAlignedValues aligned values for the
      // block approach to be worth trying
      super.fill(fromIndex, toIndex, val);
      return;
    }

    // fill the first values naively until the next block start
    final int fromIndexModNAlignedValues = fromIndex % nAlignedValues;
    if (fromIndexModNAlignedValues != 0) {
      for (int i = fromIndexModNAlignedValues; i < nAlignedValues; ++i) {
        set(fromIndex++, val);
      }
    }
    assert fromIndex % nAlignedValues == 0;

    // compute the long[] blocks for nAlignedValues consecutive values and
    // use them to set as many values as possible without applying any mask
    // or shift
    final int nAlignedBlocks = (nAlignedValues * bitsPerValue) >> 6;
    final long[] nAlignedValuesBlocks;
    {
      Packed64 values = new Packed64(nAlignedValues, bitsPerValue);
      for (int i = 0; i < nAlignedValues; ++i) {
        values.set(i, val);
      }
      nAlignedValuesBlocks = values.blocks;
      assert nAlignedBlocks <= nAlignedValuesBlocks.length;
    }
    final int startBlock = (int) (((long) fromIndex * bitsPerValue) >>> 6);
    final int endBlock = (int) (((long) toIndex * bitsPerValue) >>> 6);
    for (int  block = startBlock; block < endBlock; ++block) {
      final long blockValue = nAlignedValuesBlocks[block % nAlignedBlocks];
      blocks[block] = blockValue;
    }

    // fill the gap
    for (int i = (int) (((long) endBlock << 6) / bitsPerValue); i < toIndex; ++i) {
      set(i, val);
    }
  }

  private static int gcd(int a, int b) {
    if (a < b) {
      return gcd(b, a);
    } else if (b == 0) {
      return a;
    } else {
      return gcd(b, a % b);
    }
  }

  @Override
  public void clear() {
    Arrays.fill(blocks, 0L);
  }
}
