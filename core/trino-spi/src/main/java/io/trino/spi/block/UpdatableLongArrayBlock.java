/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.spi.block;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.function.BiConsumer;

import static io.airlift.slice.SizeOf.sizeOf;
import static io.trino.spi.block.BlockUtil.calculateBlockResetSize;
import static io.trino.spi.block.BlockUtil.checkArrayRange;
import static io.trino.spi.block.BlockUtil.checkValidRegion;
import static io.trino.spi.block.BlockUtil.countUsedPositions;
import static java.lang.Math.max;
import static java.lang.Math.toIntExact;

public class UpdatableLongArrayBlock
        implements UpdatableBlockBuilder
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(UpdatableLongArrayBlock.class).instanceSize();
    private static final Block NULL_VALUE_BLOCK = new LongArrayBlock(0, 1, new boolean[] {true}, new long[1]);

    @Nullable
    private final BlockBuilderStatus blockBuilderStatus;
    private boolean initialized;
    private final int initialEntryCount;

    private int positionCount;
    private int nullCounter = 0;
    private int deleteCounter = 0;

    // it is assumed that these arrays are the same length
    private byte[] valueMarker = new byte[0];
    private long[] values = new long[0];

    private long retainedSizeInBytes;

    private static byte NULL = 1;
    private static byte DEL = 2;

    public UpdatableLongArrayBlock(@Nullable BlockBuilderStatus blockBuilderStatus, int expectedEntries)
    {
        this.blockBuilderStatus = blockBuilderStatus;
        this.initialEntryCount = max(expectedEntries, 1);

        updateDataSize();
    }

    public UpdatableLongArrayBlock(@Nullable BlockBuilderStatus blockBuilderStatus, int positionCount, byte[] valueMarker, long[] values, int nullCounter, int deleteCounter)
    {
        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        // it is assumed that nullCounter matches the number of nulls in valueMarker
        if (nullCounter > positionCount || nullCounter < 0){
            throw new IllegalArgumentException("nullCounter is not valid");
        }
        this.nullCounter = nullCounter;

        // it is assumed that deleteCounter matches the number of nulls in valueMarker
        if (nullCounter > positionCount || nullCounter < 0){
            throw new IllegalArgumentException("deleteCounter is not valid");
        }
        this.deleteCounter = deleteCounter;

        this.values = values;

        if (valueMarker == null) {
            throw new IllegalArgumentException("isNull is Null");
        }
        this.valueMarker = valueMarker;

        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueMarker) + sizeOf(values);

        this.blockBuilderStatus = blockBuilderStatus;

        this.initialEntryCount = values.length;

    }

    @Override
    public UpdatableLongArrayBlock writeLong(long value)
    {
        if (values.length <= positionCount) {
            growCapacity();
        }

        values[positionCount] = value;

        positionCount++;
        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(Byte.BYTES + Long.BYTES);
        }
        return this;
    }

    @Override
    public UpdatableLongArrayBlock updateLong(Long value, int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be zero");
        }
        if(value != null){
            values[position] = (long) value;
            if(valueMarker[positionCount] == NULL){
                nullCounter--;
            } else if(valueMarker[positionCount] == DEL){
                deleteCounter++;
            }
            valueMarker[positionCount] = 0;
        }else{
            if(valueMarker[positionCount] != NULL){
                if(valueMarker[positionCount] == DEL){
                    deleteCounter--;
                }
                nullCounter++;
                valueMarker[positionCount] = NULL;
            }
        }
        /*if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(Byte.BYTES + Long.BYTES);
        }*/
        return this;
    }

    @Override
    public UpdatableLongArrayBlock closeEntry()
    {
        return this;
    }

    @Override
    public UpdatableLongArrayBlock appendNull()
    {
        if (values.length <= positionCount) {
            growCapacity();
        }

        valueMarker[positionCount] = NULL;

        positionCount++;
        nullCounter++;
        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(Byte.BYTES + Long.BYTES);
        }
        return this;
    }

    @Override
    public Block build()
    {
        if (deleteCounter == 0 && nullCounter == positionCount) {
            return new RunLengthEncodedBlock(NULL_VALUE_BLOCK, positionCount);
        }
        if (deleteCounter == positionCount){
            return new LongArrayBlock(0, 0, null, new long[0]);
        }
        CoreBool c = compactValuesBool();
        return new LongArrayBlock(0, c.values.length, mayHaveNull() ? c.markers : null, c.values);
    }

    @Override
    public UpdatableLongArrayBlock newBlockBuilderLike(BlockBuilderStatus blockBuilderStatus)
    {
        return new UpdatableLongArrayBlock(blockBuilderStatus, calculateBlockResetSize(positionCount));
    }

    private void growCapacity()
    {
        int newSize;
        if (initialized) {
            newSize = BlockUtil.calculateNewArraySize(values.length);
        }
        else {
            newSize = initialEntryCount;
            initialized = true;
        }

        valueMarker = Arrays.copyOf(valueMarker, newSize);
        values = Arrays.copyOf(values, newSize);
        updateDataSize();
    }

    private void updateDataSize()
    {
        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueMarker) + sizeOf(values);
        if (blockBuilderStatus != null) {
            retainedSizeInBytes += BlockBuilderStatus.INSTANCE_SIZE;
        }
    }

    @Override
    public long getSizeInBytes()
    {
        return (Long.BYTES + Byte.BYTES) * (long) positionCount;
    }

    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        return (Long.BYTES + Byte.BYTES) * (long) length;
    }

    @Override
    public long getPositionsSizeInBytes(boolean[] positions)
    {
        return (Long.BYTES + Byte.BYTES) * (long) countUsedPositions(positions);
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return retainedSizeInBytes;
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        return isNull(position) ? 0 : Long.BYTES;
    }

    @Override
    public void retainedBytesForEachPart(BiConsumer<Object, Long> consumer)
    {
        consumer.accept(values, sizeOf(values));
        consumer.accept(valueMarker, sizeOf(valueMarker));
        consumer.accept(this, (long) INSTANCE_SIZE);
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public long getLong(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be zero");
        }
        return values[position];
    }

    @Override
    @Deprecated
    // TODO: Remove when we fix intermediate types on aggregations.
    public int getInt(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be zero");
        }
        return toIntExact(values[position]);
    }

    @Override
    @Deprecated
    // TODO: Remove when we fix intermediate types on aggregations.
    public short getShort(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be zero");
        }

        short value = (short) (values[position]);
        if (value != values[position]) {
            throw new ArithmeticException("short overflow");
        }
        return value;
    }

    @Override
    @Deprecated
    // TODO: Remove when we fix intermediate types on aggregations.
    public byte getByte(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset != 0) {
            throw new IllegalArgumentException("offset must be zero");
        }

        byte value = (byte) (values[position]);
        if (value != values[position]) {
            throw new ArithmeticException("byte overflow");
        }
        return value;
    }

    @Override
    public boolean mayHaveNull()
    {
        if (nullCounter > 0){
            return true;
        }else{
            return false;
        }
    }

    @Override
    public boolean mayHaveDel()
    {
        if (deleteCounter > 0){
            return true;
        }else{
            return false;
        }
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(position);
        return valueMarker[position] == DEL;
    }

    @Override
    public void writePositionTo(int position, BlockBuilder blockBuilder)
    {
        checkReadablePosition(position);
        blockBuilder.writeLong(values[position]);
        blockBuilder.closeEntry();
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(position);
        if (valueMarker[position] != DEL){
            return new LongArrayBlock(
                    0,
                    1,
                    valueMarker[position] == NULL ? new boolean[] {true} : null,
                    new long[] {values[position]});
        }
        return new LongArrayBlock(0, 0, null, new long[0]);
    }

    /**
     * Does not return deleted values.
     */
    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        if (deleteCounter == 0 && nullCounter == positionCount) {
            return new RunLengthEncodedBlock(NULL_VALUE_BLOCK, positionCount);
        }
        if (deleteCounter == positionCount){
            return new LongArrayBlock(0, 0, null, new long[0]);
        }

        boolean[] newValueIsNull = null;
        boolean mayhavenull = mayHaveNull();
        if (mayhavenull) {
            newValueIsNull = new boolean[length];
        }
        long[] newValues = new long[length];
        int acctualSize = 0;
        for (int i = 0; i < length; i++) {
            int position = positions[offset + i];
            if (valueMarker[position] == DEL){
                continue;
            }
            acctualSize++;
            checkReadablePosition(position);
            if (mayhavenull) {
                newValueIsNull[i] = valueMarker[position] == NULL;
            }
            newValues[i] = values[position];
        }
        // could use unsafe methods to make the arrays smaller?
        return new LongArrayBlock(0, acctualSize, Arrays.copyOfRange(newValueIsNull,0, acctualSize), Arrays.copyOfRange(newValues, 0, acctualSize));
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        if (deleteCounter == 0 && nullCounter == positionCount) {
            return new RunLengthEncodedBlock(NULL_VALUE_BLOCK, positionCount);
        }
        if (deleteCounter == positionCount){
            return new LongArrayBlock(0, 0, null, new long[0]);
        }
        CoreBool c = compactValuesBool();
        return new LongArrayBlock(positionOffset, length, mayHaveNull() ? c.markers : null, c.values);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);
        if (deleteCounter == 0 && nullCounter == positionCount) {
            return new RunLengthEncodedBlock(NULL_VALUE_BLOCK, length);
        }
        if (deleteCounter == positionCount){
            return new LongArrayBlock(0, 0, null, new long[0]);
        }
        CoreBool c = compactValuesBool();
        return new LongArrayBlock(0, length, mayHaveNull() ? c.markers : null, c.values);
    }

    @Override
    public String getEncodingName()
    {
        return LongArrayBlockEncoding.NAME;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("LongArrayBlockBuilder{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append('}');
        return sb.toString();
    }

    Slice getValuesSlice()
    {
        return Slices.wrappedLongArray(values, 0, positionCount);
    }

    private void checkReadablePosition(int position)
    {
        if (position < 0 || position >= getPositionCount()) {
            throw new IllegalArgumentException("position is not valid");
        }
    }

    private void compact(){
        Core c = compactValues();
        blockBuilderStatus.addBytes(-(Byte.BYTES + Long.BYTES)*(positionCount - c.values.length));
        this.valueMarker = c.markers;
        this.values = c.values;
        this.positionCount = c.values.length;
        this.deleteCounter = 0;
        updateDataSize();

    }

    private Core compactValues(){
        long compacted[] = new long[positionCount - nullCounter];
        byte markers[] = new byte[positionCount - nullCounter];
        int cindex = 0;
        for (int i=0; i < positionCount; i++){
            if(valueMarker[i] != DEL){
                compacted[cindex] = values[i];
                markers[cindex] = valueMarker[i];
                cindex++;
            }
        }
        return new Core(compacted, markers);
    }


    private class Core{
        private long values[];
        private byte markers[];

        public Core(long values[], byte markers[]){
            this.values = values;
            this.markers = markers;
        }
    }

    private CoreBool compactValuesBool(){
        long compacted[] = new long[positionCount - nullCounter];
        boolean markers[] = new boolean[positionCount - nullCounter];
        int cindex = 0;
        for (int i=0; i < positionCount; i++){
            if(valueMarker[i] != DEL){
                compacted[cindex] = values[i];
                markers[cindex] = valueMarker[i] == NULL;
                cindex++;
            }
        }
        return new CoreBool(compacted, markers);
    }


    private class CoreBool{
        private long values[];
        private boolean markers[];

        public CoreBool(long values[], boolean markers[]){
            this.values = values;
            this.markers = markers;
        }
    }
}
