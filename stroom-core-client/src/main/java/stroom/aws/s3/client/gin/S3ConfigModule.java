/*
 * Copyright 2019 Crown Copyright
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

package stroom.aws.s3.client.gin;

import stroom.aws.s3.client.S3ConfigPlugin;
import stroom.aws.s3.client.presenter.S3ConfigPresenter;
import stroom.core.client.gin.PluginModule;

public class S3ConfigModule extends PluginModule {

    @Override
    protected void configure() {
        bindPlugin(S3ConfigPlugin.class);
        bind(S3ConfigPresenter.class);
    }
}
