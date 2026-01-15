package me.noramibu.tweaks;

import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.List;

/**
 * MixinSquared MixinCanceller to prevent Meteor Rejects' conflicting Baritone mixins from loading.
 */
public class RejectsBaritoneCancel implements MixinCanceller {
    
    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        // Cancel Rejects' MineProcessMixin to prevent @Redirect conflict
        if (mixinClassName.equals("anticope.rejects.mixin.baritone.MineProcessMixin")) {
            System.out.println("[Nora Tweaks] Cancelled Rejects' MineProcessMixin to prevent conflicts");
            return true;
        }
        
        return false;
    }
}
