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

package org.apache.shardingsphere.data.pipeline.common.job.progress;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.data.pipeline.core.ingest.position.IngestPosition;
import org.apache.shardingsphere.data.pipeline.common.task.progress.IncrementalTaskProgress;

import java.util.Optional;

/**
 * Job item incremental tasks progress.
 */
@RequiredArgsConstructor
@Getter
public final class JobItemIncrementalTasksProgress {
    
    private final IncrementalTaskProgress incrementalTaskProgress;
    
    /**
     * Get incremental position.
     *
     * @return incremental position
     */
    public Optional<IngestPosition> getIncrementalPosition() {
        return null == incrementalTaskProgress ? Optional.empty() : Optional.of(incrementalTaskProgress.getPosition());
    }
    
    /**
     * Get incremental latest active time milliseconds.
     *
     * @return latest active time, <code>0</code> means there is no activity
     */
    public long getIncrementalLatestActiveTimeMillis() {
        return null == incrementalTaskProgress ? 0L : incrementalTaskProgress.getIncrementalTaskDelay().getLatestActiveTimeMillis();
    }
}
