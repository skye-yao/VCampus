package service;

@FunctionalInterface
public interface WaitlistAdvanceTrigger {
    WaitlistAdvanceTrigger NO_OP = offeringId -> { };

    void offeringFreed(long offeringId);
}
