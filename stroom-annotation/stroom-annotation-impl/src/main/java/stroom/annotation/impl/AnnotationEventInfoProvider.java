/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.annotation.impl;

import stroom.annotation.shared.Annotation;
import stroom.annotation.shared.AnnotationDetail;
import stroom.event.logging.api.ObjectInfoProvider;
import stroom.util.shared.UserRef;

import event.logging.BaseObject;
import event.logging.OtherObject;
import event.logging.util.EventLoggingUtil;

class AnnotationEventInfoProvider implements ObjectInfoProvider {

    @Override
    public BaseObject createBaseObject(final Object obj) {
        if (obj instanceof AnnotationDetail) {
            final AnnotationDetail annotationDetail = (AnnotationDetail) obj;
            final Annotation annotation = annotationDetail.getAnnotation();

            OtherObject o = new OtherObject();
            o.setId(String.valueOf(annotation.getId()));
            o.setType("Annotation");
            o.setName(annotation.getName());
            o.setState(annotation.getStatus());

//            o.getData().add(EventLoggingUtil.createData("Stream id", String.valueOf(annotation.getStreamId())));
//            o.getData().add(EventLoggingUtil.createData("Event Id", String.valueOf(annotation.getEventId())));
            final UserRef assignedTo = annotation.getAssignedTo();
            o.getData().add(EventLoggingUtil.createData(
                    "Assigned To",
                    assignedTo != null
                            ? assignedTo.toDisplayString()
                            : null));

            return o;
        }

        return null;
    }

    @Override
    public String getObjectType(final Object object) {
        if (object == null) {
            return null;
        }
        return object.getClass().getSimpleName();
    }
}
