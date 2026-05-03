package com.speedsmp.explosionnerf;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExplosionNerfPlugin extends JavaPlugin implements Listener {

    private static final long NANOS_PER_TICK = 50_000_000L;

    private final Map<AnchorLocation, Long> activeRespawnAnchorExplosions = new ConcurrentHashMap<>();

    private double endCrystalMultiplier;
    private double respawnAnchorMultiplier;
    private double respawnAnchorDetectRadiusSquared;
    private long respawnAnchorRecordWindowNanos;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        activeRespawnAnchorExplosions.clear();
    }

    private void loadSettings() {
        reloadConfig();

        endCrystalMultiplier = Math.max(0.0D, getConfig().getDouble("end-crystal-multiplier", 0.5D));
        respawnAnchorMultiplier = Math.max(0.0D, getConfig().getDouble("respawn-anchor-multiplier", 0.5D));

        double detectRadius = Math.max(0.0D, getConfig().getDouble("respawn-anchor-detect-radius", 6.0D));
        respawnAnchorDetectRadiusSquared = detectRadius * detectRadius;

        long recordTicks = Math.max(1L, getConfig().getLong("respawn-anchor-record-ticks", 10L));
        respawnAnchorRecordWindowNanos = recordTicks * NANOS_PER_TICK;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEndCrystalDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager().getType() != EntityType.END_CRYSTAL) {
            return;
        }

        event.setDamage(event.getDamage() * endCrystalMultiplier);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRespawnAnchorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.RESPAWN_ANCHOR) {
            return;
        }

        if (event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }

        if (!doesRespawnAnchorExplode(clickedBlock.getWorld())) {
            return;
        }

        if (!isChargedRespawnAnchor(clickedBlock)) {
            return;
        }

        long now = System.nanoTime();
        cleanupExpiredRecords(now);
        activeRespawnAnchorExplosions.put(AnchorLocation.from(clickedBlock), now);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRespawnAnchorDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }

        long now = System.nanoTime();
        cleanupExpiredRecords(now);

        if (isNearRecentRespawnAnchor(event.getEntity().getLocation(), now)) {
            event.setDamage(event.getDamage() * respawnAnchorMultiplier);
        }
    }

    private boolean doesRespawnAnchorExplode(World world) {
        return world.getEnvironment() != World.Environment.NETHER;
    }

    private boolean isChargedRespawnAnchor(Block block) {
        if (!(block.getBlockData() instanceof RespawnAnchor respawnAnchor)) {
            return false;
        }

        return respawnAnchor.getCharges() > 0;
    }

    private boolean isNearRecentRespawnAnchor(Location location, long now) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        UUID worldId = world.getUID();
        for (Map.Entry<AnchorLocation, Long> entry : activeRespawnAnchorExplosions.entrySet()) {
            long recordedAt = entry.getValue();
            if (now - recordedAt > respawnAnchorRecordWindowNanos) {
                activeRespawnAnchorExplosions.remove(entry.getKey(), recordedAt);
                continue;
            }

            AnchorLocation anchorLocation = entry.getKey();
            if (!anchorLocation.worldId.equals(worldId)) {
                continue;
            }

            if (anchorLocation.distanceSquared(location) <= respawnAnchorDetectRadiusSquared) {
                return true;
            }
        }

        return false;
    }

    private void cleanupExpiredRecords(long now) {
        cleanupExpiredRecords(activeRespawnAnchorExplosions, now);
    }

    private void cleanupExpiredRecords(Map<AnchorLocation, Long> records, long now) {
        for (Map.Entry<AnchorLocation, Long> entry : records.entrySet()) {
            long recordedAt = entry.getValue();
            if (now - recordedAt > respawnAnchorRecordWindowNanos) {
                records.remove(entry.getKey(), recordedAt);
            }
        }
    }

    private static final class AnchorLocation {
        private final UUID worldId;
        private final int x;
        private final int y;
        private final int z;

        private AnchorLocation(UUID worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static AnchorLocation from(Block block) {
            return new AnchorLocation(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ()
            );
        }

        private double distanceSquared(Location location) {
            double dx = location.getX() - (x + 0.5D);
            double dy = location.getY() - (y + 0.5D);
            double dz = location.getZ() - (z + 0.5D);
            return (dx * dx) + (dy * dy) + (dz * dz);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof AnchorLocation that)) {
                return false;
            }
            return x == that.x && y == that.y && z == that.z && worldId.equals(that.worldId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, x, y, z);
        }
    }
}
