package me.noramibu.tweaks.events;

/**
 * Event fired when the player attempts to attack (before hit result check).
 * This is for older Meteor versions that don't have DoAttackEvent.
 */
public class DoAttackEvent {
    private static final DoAttackEvent INSTANCE = new DoAttackEvent();
    
    private boolean cancelled;
    
    public static DoAttackEvent get() {
        INSTANCE.cancelled = false;
        return INSTANCE;
    }
    
    public void cancel() {
        this.cancelled = true;
    }
    
    public boolean isCancelled() {
        return cancelled;
    }
}
