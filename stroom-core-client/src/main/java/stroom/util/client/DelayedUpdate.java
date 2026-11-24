/*
 * Copyright 2022 Crown Copyright
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

package stroom.util.client;

import com.google.gwt.user.client.Timer;

public class DelayedUpdate {
    private final int delay;
    private final Runnable runnable;
    private final Timer timer;

    public DelayedUpdate(final Runnable runnable) {
        this(250, runnable);
    }

    public DelayedUpdate(final int delay, final Runnable runnable) {
        this.delay = delay;
        this.runnable = runnable;
        timer = new Timer() {
            @Override
            public void run() {
                runnable.run();
            }
        };
    }

    public void reset() {
        timer.cancel();
    }

    /// Runs the update now if it is waiting to be run, and does nothing if it is not.
    public void flush() {
        if (timer.isRunning()) {
            timer.cancel();
            runnable.run();
        }
    }

    public void update() {
        if (!timer.isRunning()) {
            timer.schedule(delay);
        }
    }
}
