/*
 * Copyright 2016 Crown Copyright
 *
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

package stroom.entity.cluster;

import stroom.security.shared.UserIdentity;
import stroom.task.cluster.ClusterTask;
import stroom.util.shared.VoidResult;

public class FlushServiceClusterTask extends ClusterTask<VoidResult> {
    private static final long serialVersionUID = 3442806159160286110L;

    private Class<?> beanClass;

    public FlushServiceClusterTask(final UserIdentity userIdentity, final String taskName,
                                   final Class<?> beanClass) {
        super(userIdentity, taskName);
        this.beanClass = beanClass;
    }

    public Class<?> getBeanClass() {
        return beanClass;
    }
}
