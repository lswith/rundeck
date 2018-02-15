package rundeck.services

import com.dtolabs.rundeck.server.plugins.trigger.condition.QuartzSchedulerTaskTrigger
import org.quartz.JobBuilder
import org.quartz.JobDataMap
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.TriggerKey
import org.rundeck.core.tasks.TaskAction
import org.rundeck.core.tasks.TaskActionInvoker
import org.rundeck.core.tasks.TaskTrigger
import org.rundeck.core.tasks.TaskTriggerHandler
import rundeck.quartzjobs.RDTaskTriggerJob

/**
 * Handles trigger based schedules
 */
class ScheduledTaskTriggerService implements TaskTriggerHandler<RDTaskContext> {

    static transactional = false

    Scheduler quartzScheduler
    def scheduledExecutionService
    def executionService

    @Override
    boolean onStartup() {
        true
    }

    @Override
    boolean handlesTrigger(TaskTrigger condition, RDTaskContext contextInfo) {
        condition instanceof QuartzSchedulerTaskTrigger
    }


    @Override
    void registerTriggerForAction(String triggerId, RDTaskContext contextInfo, TaskTrigger condition, TaskAction action, TaskActionInvoker service) {
        QuartzSchedulerTaskTrigger scheduled = (QuartzSchedulerTaskTrigger) condition

        log.error("schedule trigger using quartz for $triggerId, context $contextInfo, condition $condition, action $action, invoker $service")
        scheduleTrigger(triggerId, contextInfo, scheduled, action, service)

    }

    @Override
    void deregisterTriggerForAction(String triggerId, RDTaskContext contextInfo, TaskTrigger condition, TaskAction action, TaskActionInvoker service) {

        log.error("deregister trigger from quartz for $triggerId, context $contextInfo, condition $condition, action $action, invoker $service")
        unscheduleTrigger(triggerId, contextInfo)
    }

    boolean unscheduleTrigger(String triggerId, RDTaskContext contextInfo) {

        def quartzJobName = triggerId
        def quartzJobGroup = 'ScheduledTaskTriggerService'
        if (quartzScheduler.checkExists(JobKey.jobKey(quartzJobName, quartzJobGroup))) {
            log.info("Removing existing trigger $triggerId with context $contextInfo: " + quartzJobName)

            return quartzScheduler.unscheduleJob(TriggerKey.triggerKey(quartzJobName, quartzJobGroup))
        }
        false
    }

    Date scheduleTrigger(String triggerId, RDTaskContext contextInfo, QuartzSchedulerTaskTrigger scheduled, TaskAction action, TaskActionInvoker invoker) {

        def quartzJobName = triggerId
        def quartzJobGroup = 'ScheduledTaskTriggerService'
        def jobDesc = "Attempt to schedule job $quartzJobName with context $contextInfo"
        if (!executionService.executionsAreActive) {
            log.warn("$jobDesc, but executions are disabled.")
            return null
        }

        if (!scheduledExecutionService.shouldScheduleInThisProject(contextInfo.project)) {
            log.warn("$jobDesc, but project executions are disabled.")
            return null
        }


        def jobDetailBuilder = JobBuilder.newJob(RDTaskTriggerJob)
                .withIdentity(quartzJobName, quartzJobGroup)
//                .withDescription(scheduled.)
                .usingJobData(new JobDataMap(createJobDetailMap(triggerId, contextInfo, scheduled, action, invoker)))


        def jobDetail = jobDetailBuilder.build()
        def trigger = scheduled.createQuartzTrigger(quartzJobName, quartzJobGroup)

        def Date nextTime

        if (quartzScheduler.checkExists(JobKey.jobKey(quartzJobName, quartzJobGroup))) {
            log.info("rescheduling existing trigger $triggerId with context $contextInfo: " + quartzJobName)

            nextTime = quartzScheduler.rescheduleJob(TriggerKey.triggerKey(quartzJobName, quartzJobGroup), trigger)
        } else {
            log.info("scheduling new trigger $triggerId with context $contextInfo: " + quartzJobName)
            nextTime = quartzScheduler.scheduleJob(jobDetail, trigger)
        }

        log.info("scheduled trigger $triggerId. next run: " + nextTime.toString())
        return nextTime
    }

    Map createJobDetailMap(String triggerId, RDTaskContext contextInfo, QuartzSchedulerTaskTrigger scheduled, TaskAction action, TaskActionInvoker invoker) {
        [
                triggerId: triggerId,
                context  : contextInfo,
                condition: scheduled,
                action   : action,
                invoker  : invoker
        ]
    }
}
