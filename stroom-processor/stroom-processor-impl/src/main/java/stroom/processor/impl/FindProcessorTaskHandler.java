package stroom.processor.impl;

import event.logging.BaseAdvancedQueryOperator.And;
import event.logging.Query;
import event.logging.Query.Advanced;
import stroom.event.logging.api.DocumentEventLog;
import stroom.processor.api.ProcessorTaskService;
import stroom.processor.shared.FindProcessorTaskAction;
import stroom.processor.shared.FindProcessorTaskCriteria;
import stroom.processor.shared.ProcessorTask;
import stroom.security.Security;
import stroom.task.api.AbstractTaskHandler;
import stroom.util.shared.BaseResultList;
import stroom.util.shared.ResultList;

import javax.inject.Inject;

class FindProcessorTaskHandler extends AbstractTaskHandler<FindProcessorTaskAction, ResultList<ProcessorTask>> {
    private final ProcessorTaskService processorTaskService;
    private final DocumentEventLog entityEventLog;
    private final Security security;

    @Inject
    FindProcessorTaskHandler(final ProcessorTaskService processorTaskService,
                             final DocumentEventLog entityEventLog,
                             final Security security) {
        this.processorTaskService = processorTaskService;
        this.entityEventLog = entityEventLog;
        this.security = security;
    }

    @Override
    public ResultList<ProcessorTask> exec(final FindProcessorTaskAction action) {
        final FindProcessorTaskCriteria criteria = action.getCriteria();
        return security.secureResult(() -> {
            BaseResultList<ProcessorTask> result;

            final Query query = new Query();
            final Advanced advanced = new Advanced();
            query.setAdvanced(advanced);
            final And and = new And();
            advanced.getAdvancedQueryItems().add(and);

            try {
                result = processorTaskService.find(criteria);
                entityEventLog.search(criteria, query, result, null);
            } catch (final RuntimeException e) {
                entityEventLog.search(criteria, query, null, e);
                throw e;
            }

            return result;
        });
    }
}
