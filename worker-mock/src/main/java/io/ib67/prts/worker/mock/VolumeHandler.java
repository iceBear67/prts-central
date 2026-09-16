package io.ib67.prts.worker.mock;

import io.ib67.prts.worker.mock.protocol.Inbound;

/**
 * What a mock worker does when asked for a volume.
 *
 * <p>Returning null acknowledges the request; anything else is the reason it is refused, and the
 * control plane drops a volume row it could not provision.
 */
public interface VolumeHandler {

    /** Allocates whatever is asked for. */
    VolumeHandler ACCEPT = new VolumeHandler() {
    };

    /** @return null to acknowledge, or why the allocation is refused */
    default String onCreate(Inbound.CreateVolume request) {
        return null;
    }

    /** @return null to acknowledge, or why the release is refused */
    default String onDelete(Inbound.DeleteVolume request) {
        return null;
    }

    /** Refuses every volume operation with one reason, the way a host out of disk would. */
    static VolumeHandler refusing(String reason) {
        return new VolumeHandler() {
            @Override
            public String onCreate(Inbound.CreateVolume request) {
                return reason;
            }

            @Override
            public String onDelete(Inbound.DeleteVolume request) {
                return reason;
            }
        };
    }
}
