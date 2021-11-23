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
package io.trino.split;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.trino.connector.CatalogName;
import io.trino.execution.Lifespan;
import io.trino.metadata.DeltaSplit;
import io.trino.metadata.Split;
import io.trino.spi.connector.ConnectorDeltaSplit;
import io.trino.spi.connector.ConnectorPartitionHandle;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorSplitDeltaSource;
import io.trino.spi.connector.ConnectorSplitSource;
import io.trino.spi.connector.ConnectorSplitSource.ConnectorSplitBatch;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.MoreFutures.toListenableFuture;
import static java.util.Objects.requireNonNull;

public class ConnectorAwareSplitDeltaSource
        implements SplitDeltaSource
{
    private final CatalogName catalogName;
    private final ConnectorSplitDeltaSource source;

    public ConnectorAwareSplitDeltaSource(CatalogName catalogName, ConnectorSplitDeltaSource source)
    {
        this.catalogName = requireNonNull(catalogName, "catalogName is null");
        this.source = requireNonNull(source, "source is null");
    }

    @Override
    public CatalogName getCatalogName()
    {
        return catalogName;
    }

    @Override
    public ListenableFuture<SplitDeltaBatch> getNextDeltaBatch(ConnectorPartitionHandle partitionHandle, Lifespan lifespan, int maxSize)
    {
        ListenableFuture<ConnectorSplitDeltaSource.ConnectorSplitDeltaBatch> nextBatch = toListenableFuture(source.getNextDeltaBatch(partitionHandle, maxSize));
        return Futures.transform(nextBatch, splitBatch -> {
            ImmutableList.Builder<DeltaSplit> result = ImmutableList.builder();
            for (ConnectorDeltaSplit connectorSplit : splitBatch.getDeltaSplits()) {
                result.add(new DeltaSplit(catalogName, connectorSplit, lifespan));
            }
            return new SplitDeltaBatch(result.build(), splitBatch.isNoMoreSplits());
        }, directExecutor());
    }

    @Override
    public void close()
    {
        source.close();
    }

    @Override
    public boolean isFinished()
    {
        return source.isFinished();
    }

    @Override
    public String toString()
    {
        return catalogName + ":" + source;
    }
}
