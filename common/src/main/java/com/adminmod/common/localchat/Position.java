package com.adminmod.common.localchat;

/**
 * An immutable world position, used for proximity distance checks.
 *
 * <p>A plain record rather than Minecraft's {@code Vec3d} so the distance rule can be unit-tested
 * without loading any game class. The server copies the two positions it needs out of the player
 * entities and works on these.
 */
public record Position(double x, double y, double z) {

    /** Squared distance to another position. Squared, so no square root is needed to compare. */
    public double squaredDistanceTo(Position other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Whether this position is within {@code radius} blocks of {@code other}.
     *
     * <p>The comparison is inclusive: a player exactly on the boundary hears the message. A radius
     * of zero or less matches only an identical position.
     */
    public boolean isWithin(Position other, double radius) {
        if (other == null) {
            return false;
        }
        double limit = radius <= 0 ? 0 : radius * radius;
        return squaredDistanceTo(other) <= limit;
    }
}
