/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.fielddata.plain;

import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.index.fielddata.AtomicFieldData;
import org.elasticsearch.index.fielddata.BytesValues;

import java.io.IOException;

/**
 * {@link AtomicFieldData} impl based on Lucene's {@link SortedSetDocValues}.
 * <p><b>Implementation note</b>: Lucene's ordinal for unset values is -1 whereas Elasticsearch's is 0, this is why there are all
 * these +1 to translate from Lucene's ordinals to ES's.
 */
abstract class SortedSetDVAtomicFieldData {

    private final AtomicReader reader;
    private final String field;

    SortedSetDVAtomicFieldData(AtomicReader reader, String field) {
        this.reader = reader;
        this.field = field;
    }

    public boolean isMultiValued() {
        // we could compute it when loading the values for the first time and then cache it but it would defeat the point of
        // doc values which is to make loading faster
        return true;
    }

    public long getNumberUniqueValues() {
        final SortedSetDocValues values = getValuesNoException(reader, field);
        return values.getValueCount();
    }

    public long getMemorySizeInBytes() {
        // There is no API to access memory usage per-field and RamUsageEstimator can't help since there are often references
        // from a per-field instance to all other instances handled by the same format
        return -1L;
    }

    public void close() {
        // no-op
    }

    public org.elasticsearch.index.fielddata.BytesValues.WithOrdinals getBytesValues() {
        final SortedSetDocValues values = getValuesNoException(reader, field);
        return new SortedSetValues(values);
    }

    public TermsEnum getTermsEnum() {
        return getValuesNoException(reader, field).termsEnum();
    }

    private static SortedSetDocValues getValuesNoException(AtomicReader reader, String field) {
        try {
            SortedSetDocValues values = reader.getSortedSetDocValues(field);
            if (values == null) {
                // This field has not been populated
                assert reader.getFieldInfos().fieldInfo(field) == null;
                values = DocValues.EMPTY_SORTED_SET;
            }
            return values;
        } catch (IOException e) {
            throw new ElasticsearchIllegalStateException("Couldn't load doc values", e);
        }
    }

    static class SortedSetValues extends BytesValues.WithOrdinals {

        private final BytesRef scratch = new BytesRef();
        private final SortedSetDocValues values;
        private long[] ords;
        private int ordIndex = Integer.MAX_VALUE;

        SortedSetValues(SortedSetDocValues values) {
            super(DocValues.unwrapSingleton(values) == null);
            this.values = values;
            ords = new long[0];
        }

        @Override
        public long getMaxOrd() {
            return values.getValueCount();
        }

        @Override
        public long getOrd(int docId) {
            values.setDocument(docId);
            return values.nextOrd();
        }

        @Override
        public long nextOrd() {
            assert ordIndex < ords.length;
            return ords[ordIndex++];
        }

        @Override
        public int setDocument(int docId) {
            // For now, we consume all ords and pass them to the iter instead of doing it in a streaming way because Lucene's
            // SORTED_SET doc values are cached per thread, you can't have a fully independent instance
            values.setDocument(docId);
            int i = 0;
            for (long ord = values.nextOrd(); ord != SortedSetDocValues.NO_MORE_ORDS; ord = values.nextOrd()) {
                ords = ArrayUtil.grow(ords, i + 1);
                ords[i++] = ord;
            }
            ordIndex = 0;
            return i;
        }

        @Override
        public BytesRef getValueByOrd(long ord) {
            values.lookupOrd(ord, scratch);
            return scratch;
        }
    }
}
