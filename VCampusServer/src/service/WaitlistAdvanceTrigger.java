package service;

@FunctionalInterface
/**
* Internal course-management type WaitlistAdvanceTrigger.
*/
public interface WaitlistAdvanceTrigger {
    WaitlistAdvanceTrigger NO_OP = offeringId -> { };

    void offeringFreed(long offeringId);
}
