package com.massivecraft.factions.listeners;

import com.massivecraft.factions.*;
import com.massivecraft.factions.cmd.CmdFly;
import com.massivecraft.factions.event.FPlayerEnteredFactionEvent;
import com.massivecraft.factions.event.FPlayerJoinEvent;
import com.massivecraft.factions.event.FPlayerLeaveEvent;
import com.massivecraft.factions.scoreboards.FScoreboard;
import com.massivecraft.factions.scoreboards.FTeamWrapper;
import com.massivecraft.factions.scoreboards.sidebar.FDefaultSidebar;
import com.massivecraft.factions.struct.Permission;
import com.massivecraft.factions.struct.Relation;
import com.massivecraft.factions.struct.Role;
import com.massivecraft.factions.util.Particle.ParticleEffect;
import com.massivecraft.factions.util.VisualizeUtil;
import com.massivecraft.factions.zcore.fperms.Access;
import com.massivecraft.factions.zcore.fperms.PermissableAction;
import com.massivecraft.factions.util.FactionGUI;
import com.massivecraft.factions.zcore.persist.MemoryFPlayer;
import com.massivecraft.factions.zcore.util.TL;
import com.massivecraft.factions.zcore.util.TextUtil;
import com.sun.org.apache.xerces.internal.xs.StringList;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.NumberConversions;

import java.util.*;
import java.util.logging.Level;

public class FactionsPlayerListener implements Listener {

    private P p;

    public FactionsPlayerListener(P p) {
        this.p = p;
        for (Player player : p.getServer().getOnlinePlayers()) {
            initPlayer(player);
        }
    }

    @EventHandler
    public void onVaultPlace(BlockPlaceEvent e) {
        if (e.getItemInHand().getType() == Material.CHEST) {
            ItemStack vault = P.p.createItem(Material.CHEST, 1, (short) 0, P.p.color(P.p.getConfig().getString("fvault.Item.Name")), P.p.colorList(P.p.getConfig().getStringList("fvault.Item.Lore")));
            if (e.getItemInHand().equals(vault)) {
                FPlayer fme = FPlayers.getInstance().getByPlayer(e.getPlayer());
                if (fme.getFaction().getVault() != null) {
                    fme.msg(TL.COMMAND_GETVAULT_ALREADYSET);
                    e.setCancelled(true);
                    return;
                }
                FLocation flocation = new FLocation(e.getBlockPlaced().getLocation());
                if (Board.getInstance().getFactionAt(flocation) != fme.getFaction()) {
                    fme.msg(TL.COMMAND_GETVAULT_INVALIDLOCATION);
                    e.setCancelled(true);
                    return;
                }
                Block start = e.getBlockPlaced();
                int radius = 1;
                for (double x = start.getLocation().getX() - radius; x <= start.getLocation().getX() + radius; x++) {
                    for (double y = start.getLocation().getY() - radius; y <= start.getLocation().getY() + radius; y++) {
                        for (double z = start.getLocation().getZ() - radius; z <= start.getLocation().getZ() + radius; z++) {
                            Location blockLoc = new Location(e.getPlayer().getWorld(), x, y, z);
                            if (blockLoc.getX() == start.getLocation().getX() && blockLoc.getY() == start.getLocation().getY() && blockLoc.getZ() == start.getLocation().getZ()) {
                                continue;
                            }

                            if (blockLoc.getBlock().getType() == Material.CHEST) {
                                e.setCancelled(true);
                                fme.msg(TL.COMMAND_GETVAULT_CHESTNEAR);
                                return;
                            }
                        }
                    }
                }

                fme.msg(TL.COMMAND_GETVAULT_SUCCESS);
                fme.getFaction().setVault(e.getBlockPlaced().getLocation());

            }
        }
    }


    HashMap<Player, Boolean> fallMap = new HashMap<>();

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        initPlayer(event.getPlayer());
    }

    private void initPlayer(Player player) {
        // Make sure that all online players do have a fplayer.
        final FPlayer me = FPlayers.getInstance().getByPlayer(player);
        ((MemoryFPlayer) me).setName(player.getName());

        // Update the lastLoginTime for this fplayer
        me.setLastLoginTime(System.currentTimeMillis());

        // Store player's current FLocation and notify them where they are
        me.setLastStoodAt(new FLocation(player.getLocation()));

        me.login(); // set kills / deaths

        // Check for Faction announcements. Let's delay this so they actually see it.
        Bukkit.getScheduler().runTaskLater(P.p, new Runnable() {
            @Override
            public void run() {
                if (me.isOnline()) {
                    me.getFaction().sendUnreadAnnouncements(me);
                }
            }
        }, 33L); // Don't ask me why.

        if (P.p.getConfig().getBoolean("scoreboard.default-enabled", false)) {
            FScoreboard.init(me);
            FScoreboard.get(me).setDefaultSidebar(new FDefaultSidebar(), P.p.getConfig().getInt("default-update-interval", 20));
            FScoreboard.get(me).setSidebarVisibility(me.showScoreboard());
        }

        Faction myFaction = me.getFaction();
        if (!myFaction.isWilderness()) {
            for (FPlayer other : myFaction.getFPlayersWhereOnline(true)) {
                if (other != me && other.isMonitoringJoins()) {
                    other.msg(TL.FACTION_LOGIN, me.getName());
                }
            }
        }

        fallMap.put(me.getPlayer(), false);
        Bukkit.getScheduler().scheduleSyncDelayedTask(P.p, new Runnable() {
            @Override
            public void run() {
                if (fallMap.containsKey(me.getPlayer())) {
                    fallMap.remove(me.getPlayer());
                }

            }
        }, 180L);


        if (me.isSpyingChat() && !player.hasPermission(Permission.CHATSPY.node)) {
            me.setSpyingChat(false);
            P.p.log(Level.INFO, "Found %s spying chat without permission on login. Disabled their chat spying.", player.getName());
        }

        if (me.isAdminBypassing() && !player.hasPermission(Permission.BYPASS.node)) {
            me.setIsAdminBypassing(false);
            P.p.log(Level.INFO, "Found %s on admin Bypass without permission on login. Disabled it for them.", player.getName());
        }


        // If they have the permission, don't let them autoleave. Bad inverted setter :\
        me.setAutoLeave(!player.hasPermission(Permission.AUTO_LEAVE_BYPASS.node));
        me.setTakeFallDamage(true);
    }

    @EventHandler
    public void onPlayerFall(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) {
            if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
                Player player = (Player) e.getEntity();
                if (fallMap.containsKey(player)) {
                    e.setCancelled(true);
                    fallMap.remove(player);
                }
            }
        }
    }


    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        FPlayer me = FPlayers.getInstance().getByPlayer(event.getPlayer());

        // Make sure player's power is up to date when they log off.
        me.getPower();
        // and update their last login time to point to when the logged off, for auto-remove routine
        me.setLastLoginTime(System.currentTimeMillis());

        me.logout(); // cache kills / deaths

        // if player is waiting for fstuck teleport but leaves, remove
        if (P.p.getStuckMap().containsKey(me.getPlayer().getUniqueId())) {
            FPlayers.getInstance().getByPlayer(me.getPlayer()).msg(TL.COMMAND_STUCK_CANCELLED);
            P.p.getStuckMap().remove(me.getPlayer().getUniqueId());
            P.p.getTimers().remove(me.getPlayer().getUniqueId());
        }

        Faction myFaction = me.getFaction();
        if (!myFaction.isWilderness()) {
            myFaction.memberLoggedOff();
        }

        if (!myFaction.isWilderness()) {
            for (FPlayer player : myFaction.getFPlayersWhereOnline(true)) {
                if (player != me && player.isMonitoringJoins()) {
                    player.msg(TL.FACTION_LOGOUT, me.getName());
                }
            }
        }

        FScoreboard.remove(me);
    }

    public String parseAllPlaceholders(String string, Faction faction) {
        string = string.replace("{Faction}", faction.getTag())
                .replace("{online}", faction.getOnlinePlayers().size() + "")
                .replace("{offline}", faction.getFPlayers().size() - faction.getOnlinePlayers().size() + "")
                .replace("{chunks}", faction.getAllClaims().size() + "")
                .replace("{power}", faction.getPower() + "")
                .replace("{leader}", faction.getFPlayerAdmin() + "");


        return string;
    }

    // Holds the next time a player can have a map shown.
    private HashMap<UUID, Long> showTimes = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        FPlayer me = FPlayers.getInstance().getByPlayer(player);

        // clear visualization
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockY() != event.getTo().getBlockY() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            VisualizeUtil.clear(event.getPlayer());
            if (me.isWarmingUp()) {
                me.clearWarmup();
                me.msg(TL.WARMUPS_CANCELLED);
            }
        }

        // quick check to make sure player is moving between chunks; good performance boost
        if (event.getFrom().getBlockX() >> 4 == event.getTo().getBlockX() >> 4 && event.getFrom().getBlockZ() >> 4 == event.getTo().getBlockZ() >> 4 && event.getFrom().getWorld() == event.getTo().getWorld()) {
            return;
        }

        // Did we change coord?
        FLocation from = me.getLastStoodAt();
        FLocation to = new FLocation(event.getTo());

        if (from.equals(to)) {
            return;
        }

        // Yes we did change coord (:

        me.setLastStoodAt(to);

        // Did we change "host"(faction)?
        Faction factionFrom = Board.getInstance().getFactionAt(from);
        Faction factionTo = Board.getInstance().getFactionAt(to);
        boolean changedFaction = (factionFrom != factionTo);
        if (changedFaction) {
            Bukkit.getServer().getPluginManager().callEvent(new FPlayerEnteredFactionEvent(factionTo,factionFrom,me));
            if (P.p.getConfig().getBoolean("Title.Show-Title")) {
                String title = P.p.getConfig().getString("Title.Format.Title");
                title = title.replace("{Faction}", factionTo.getColorTo(me) + factionTo.getTag());
                title = parseAllPlaceholders(title, factionTo);
                String subTitle = P.p.getConfig().getString("Title.Format.Subtitle").replace("{Description}", factionTo.getDescription()).replace("{Faction}", factionTo.getColorTo(me) + factionTo.getTag());
                subTitle = parseAllPlaceholders(subTitle, factionTo);
                me.getPlayer().sendTitle(P.p.color(title), P.p.color(subTitle));
            }
            // enable fly :)
            if (me.hasFaction()) {
                if (factionTo == me.getFaction()) {
                    if (P.p.getConfig().getBoolean("ffly.AutoEnable")) {
                        CmdFly Fly = new CmdFly();
                        me.setFlying(true);
                        Fly.flyMap.put(player.getName(), true);
                        if (Fly.id == -1) {
                            if (P.p.getConfig().getBoolean("ffly.Particles.Enabled")) {
                                Fly.startParticles();
                            }
                        }
                        if (Fly.flyid == -1) {
                            Fly.startFlyCheck();
                        }
                    }
                }
            }
        }


        if (me.isMapAutoUpdating()) {
            if (showTimes.containsKey(player.getUniqueId()) && (showTimes.get(player.getUniqueId()) > System.currentTimeMillis())) {
                if (P.p.getConfig().getBoolean("findfactionsexploit.log", false)) {
                    P.p.log(Level.WARNING, "%s tried to show a faction map too soon and triggered exploit blocker.", player.getName());
                }
            } else {
                me.sendFancyMessage(Board.getInstance().getMap(me, to, player.getLocation().getYaw()));
                showTimes.put(player.getUniqueId(), System.currentTimeMillis() + P.p.getConfig().getLong("findfactionsexploit.cooldown", 2000));
            }
        } else {
            Faction myFaction = me.getFaction();
            String ownersTo = myFaction.getOwnerListString(to);

            if (changedFaction) {
                me.sendFactionHereMessage(factionFrom);
                if (Conf.ownedAreasEnabled && Conf.ownedMessageOnBorder && myFaction == factionTo && !ownersTo.isEmpty()) {
                    me.sendMessage(TL.GENERIC_OWNERS.format(ownersTo));
                }
            } else if (Conf.ownedAreasEnabled && Conf.ownedMessageInsideTerritory && myFaction == factionTo && !myFaction.isWilderness()) {
                String ownersFrom = myFaction.getOwnerListString(from);
                if (Conf.ownedMessageByChunk || !ownersFrom.equals(ownersTo)) {
                    if (!ownersTo.isEmpty()) {
                        me.sendMessage(TL.GENERIC_OWNERS.format(ownersTo));
                    } else if (!TL.GENERIC_PUBLICLAND.toString().isEmpty()) {
                        me.sendMessage(TL.GENERIC_PUBLICLAND.toString());
                    }
                }
            }
        }

        if (me.getAutoClaimFor() != null) {
            me.attemptClaim(me.getAutoClaimFor(), event.getTo(), true);
        } else if (me.isAutoSafeClaimEnabled()) {
            if (!Permission.MANAGE_SAFE_ZONE.has(player)) {
                me.setIsAutoSafeClaimEnabled(false);
            } else {
                if (!Board.getInstance().getFactionAt(to).isSafeZone()) {
                    Board.getInstance().setFactionAt(Factions.getInstance().getSafeZone(), to);
                    me.msg(TL.PLAYER_SAFEAUTO);
                }
            }
        } else if (me.isAutoWarClaimEnabled()) {
            if (!Permission.MANAGE_WAR_ZONE.has(player)) {
                me.setIsAutoWarClaimEnabled(false);
            } else {
                if (!Board.getInstance().getFactionAt(to).isWarZone()) {
                    Board.getInstance().setFactionAt(Factions.getInstance().getWarZone(), to);
                    me.msg(TL.PLAYER_WARAUTO);
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        FPlayer fme = FPlayers.getInstance().getById(e.getPlayer().getUniqueId().toString());
        if (fme.isInVault()) {
            fme.setInVault(false);
        }

    }

    HashMap<String,Boolean> bannerCooldownMap = new HashMap<>();
    public static HashMap<String,Location> bannerLocations = new HashMap<>();
    @EventHandler
    public void onBannerPlace(BlockPlaceEvent e){
        if (e.getItemInHand().getType() == Material.BANNER){
            ItemStack bannerInHand = e.getItemInHand();
            ItemStack warBanner = P.p.createItem(bannerInHand.getType(),1,bannerInHand.getDurability(),P.p.getConfig().getString("fbanners.Item.Name"),P.p.getConfig().getStringList("fbanners.Item.Lore"));
            if (warBanner.isSimilar(bannerInHand)){
                FPlayer fme = FPlayers.getInstance().getByPlayer(e.getPlayer());
                if (fme.getFaction().isWilderness()){
                    fme.msg(TL.WARBANNER_NOFACTION);
                    e.setCancelled(true);
                    return;
                }
                int bannerTime = P.p.getConfig().getInt("fbanners.Banner-Time")*20;

                Location placedLoc = e.getBlockPlaced().getLocation();
                FLocation fplacedLoc = new FLocation(placedLoc);
                if (Board.getInstance().getFactionAt(fplacedLoc).isWarZone() || fme.getFaction().getRelationTo(Board.getInstance().getFactionAt(fplacedLoc)) == Relation.ENEMY){
                    if (bannerCooldownMap.containsKey(fme.getTag())){
                        fme.msg(TL.WARBANNER_COOLDOWN);
                        e.setCancelled(true);
                        return;
                    }
                    for (FPlayer fplayer : fme.getFaction().getFPlayers()){
                        //  if (fplayer == fme) { continue; }   //Idk if I wanna not send the title to the player
                        fplayer.getPlayer().sendTitle(P.p.color(fme.getTag() + " Placed A WarBanner!"),P.p.color("&7use &c/f tpbanner&7 to tp to the banner!"));

                    }
                    bannerCooldownMap.put(fme.getTag(),true);
                    bannerLocations.put(fme.getTag(),e.getBlockPlaced().getLocation());
                    int bannerCooldown = P.p.getConfig().getInt("fbanners.Banner-Place-Cooldown");
                    final ArmorStand as = (ArmorStand) e.getBlockPlaced().getLocation().add(0.5,1,0.5).getWorld().spawnEntity(e.getBlockPlaced().getLocation().add(0.5,1,0.5), EntityType.ARMOR_STAND); //Spawn the ArmorStand
                    as.setVisible(false); //Makes the ArmorStand invisible
                    as.setGravity(false); //Make sure it doesn't fall
                    as.setCanPickupItems(false); //I'm not sure what happens if you leave this as it is, but you might as well disable it
                    as.setCustomName(P.p.color(P.p.getConfig().getString("fbanners.BannerHolo").replace("{Faction}",fme.getTag()))); //Set this to the text you want
                    as.setCustomNameVisible(true); //This makes the text appear no matter if your looking at the entity or not
                    final ArmorStand armorStand = as;
                    final String tag = fme.getTag();
                    Bukkit.getScheduler().scheduleSyncDelayedTask(P.p, new Runnable() {
                        @Override
                        public void run() {
                            bannerCooldownMap.remove(tag);
                        }
                    }, Long.parseLong(bannerCooldown + ""));
                    final Block banner = e.getBlockPlaced();
                    final Material bannerType = banner.getType();
                    final Faction bannerFaction = fme.getFaction();
                    banner.getWorld().strikeLightningEffect(banner.getLocation());
                    //  e.getPlayer().getWorld().playSound(e.getPlayer().getLocation(), Sound.ENTITY_LIGHTNING_IMPACT,2.0F,0.5F);
                    final int radius = P.p.getConfig().getInt("fbanners.Banner-Effect-Radius");
                    final List<String> effects = P.p.getConfig().getStringList("fbanners.Effects");
                    final int affectorTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(P.p, new Runnable() {
                        @Override
                        public void run() {

                            for (Entity e : banner.getLocation().getWorld().getNearbyEntities(banner.getLocation(),radius,255,radius)){
                                if (e instanceof Player){
                                    Player player = (Player) e;
                                    FPlayer fplayer = FPlayers.getInstance().getByPlayer(player);
                                    if (fplayer.getFaction() == bannerFaction){
                                        for (String effect : effects){
                                            String[] components = effect.split(":");
                                            player.addPotionEffect(new PotionEffect(PotionEffectType.getByName(components[0]),100,Integer.parseInt(components[1])));
                                        }
                                        ParticleEffect.FLAME.display(1,1,1,1,10,banner.getLocation(),16);
                                        ParticleEffect.LAVA.display(1,1,1,1,10,banner.getLocation(),16);
                                        if (banner.getType() != bannerType){
                                            banner.setType(bannerType);
                                        }
                                    }
                                }
                            }
                        }
                    },0L,20L);
                    Bukkit.getScheduler().scheduleSyncDelayedTask(P.p, new Runnable() {
                        @Override
                        public void run() {
                            banner.setType(Material.AIR);
                            as.remove();
                            banner.getWorld().strikeLightningEffect(banner.getLocation());
                            Bukkit.getScheduler().cancelTask(affectorTask);
                            bannerLocations.remove(bannerFaction.getTag());
                        }
                    },Long.parseLong(bannerTime + ""));
                }
                else {
                    fme.msg(TL.WARBANNER_INVALIDLOC);
                    e.setCancelled(true);
                }
            }
        }
    }








    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // only need to check right-clicks and physical as of MC 1.4+; good performance boost
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.PHYSICAL) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        if (block == null) {
            return;  // clicked in air, apparently
        }

        if (!canPlayerUseBlock(player, block, false)) {
            event.setCancelled(true);
            if (Conf.handleExploitInteractionSpam) {
                String name = player.getName();
                InteractAttemptSpam attempt = interactSpammers.get(name);
                if (attempt == null) {
                    attempt = new InteractAttemptSpam();
                    interactSpammers.put(name, attempt);
                }
                int count = attempt.increment();
                if (count >= 10) {
                    FPlayer me = FPlayers.getInstance().getByPlayer(player);
                    me.msg(TL.PLAYER_OUCH);
                    player.damage(NumberConversions.floor((double) count / 10));
                }
            }
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;  // only interested on right-clicks for below
        }

        if (!playerCanUseItemHere(player, block.getLocation(), event.getMaterial(), false)) {
            event.setCancelled(true);
        }
    }


    // for handling people who repeatedly spam attempts to open a door (or similar) in another faction's territory
    private Map<String, InteractAttemptSpam> interactSpammers = new HashMap<>();

    private static class InteractAttemptSpam {
        private int attempts = 0;
        private long lastAttempt = System.currentTimeMillis();

        // returns the current attempt count
        public int increment() {
            long Now = System.currentTimeMillis();
            if (Now > lastAttempt + 2000) {
                attempts = 1;
            } else {
                attempts++;
            }
            lastAttempt = Now;
            return attempts;
        }
    }


    public static boolean playerCanUseItemHere(Player player, Location location, Material material, boolean justCheck) {
        String name = player.getName();
        if (Conf.playersWhoBypassAllProtection.contains(name)) {
            return true;
        }

        FPlayer me = FPlayers.getInstance().getByPlayer(player);
        if (me.isAdminBypassing()) {
            return true;
        }

        FLocation loc = new FLocation(location);
        Faction otherFaction = Board.getInstance().getFactionAt(loc);

        if (P.p.getConfig().getBoolean("hcf.raidable", false) && otherFaction.getLandRounded() >= otherFaction.getPowerRounded()) {
            return true;
        }

        if (otherFaction.hasPlayersOnline()) {
            if (!Conf.territoryDenyUseageMaterials.contains(material)) {
                return true; // Item isn't one we're preventing for online factions.
            }
        } else {
            if (!Conf.territoryDenyUseageMaterialsWhenOffline.contains(material)) {
                return true; // Item isn't one we're preventing for offline factions.
            }
        }

        if (otherFaction.isWilderness()) {
            if (!Conf.wildernessDenyUseage || Conf.worldsNoWildernessProtection.contains(location.getWorld().getName())) {
                return true; // This is not faction territory. Use whatever you like here.
            }

            if (!justCheck) {
                me.msg(TL.PLAYER_USE_WILDERNESS, TextUtil.getMaterialName(material));
            }

            return false;
        } else if (otherFaction.isSafeZone()) {
            if (!Conf.safeZoneDenyUseage || Permission.MANAGE_SAFE_ZONE.has(player)) {
                return true;
            }

            if (!justCheck) {
                me.msg(TL.PLAYER_USE_SAFEZONE, TextUtil.getMaterialName(material));
            }

            return false;
        } else if (otherFaction.isWarZone()) {
            if (!Conf.warZoneDenyUseage || Permission.MANAGE_WAR_ZONE.has(player)) {
                return true;
            }

            if (!justCheck) {
                me.msg(TL.PLAYER_USE_WARZONE, TextUtil.getMaterialName(material));
            }

            return false;
        }

        Faction myFaction = me.getFaction();
        Relation rel = myFaction.getRelationTo(otherFaction);


        // Cancel if we are not in our own territory
        if (rel.confDenyUseage()) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_TERRITORY, TextUtil.getMaterialName(material), otherFaction.getTag(myFaction));
            }

            return false;
        }
        Access access = otherFaction.getAccess(me, PermissableAction.ITEM);
        if (access != null && access != Access.UNDEFINED) {
            // TODO: Update this once new access values are added other than just allow / deny.
            return access == Access.ALLOW;
        }

        // Also cancel if player doesn't have ownership rights for this claim
        if (Conf.ownedAreasEnabled && Conf.ownedAreaDenyUseage && !otherFaction.playerHasOwnershipRights(me, loc)) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_OWNED, TextUtil.getMaterialName(material), otherFaction.getOwnerListString(loc));
            }

            return false;
        }

        return true;
    }

    public static boolean canPlayerUseBlock(Player player, Block block, boolean justCheck) {
        if (Conf.playersWhoBypassAllProtection.contains(player.getName())) {
            return true;
        }

        FPlayer me = FPlayers.getInstance().getByPlayer(player);
        if (me.isAdminBypassing()) {
            return true;
        }

        Material material = block.getType();
        FLocation loc = new FLocation(block);
        Faction otherFaction = Board.getInstance().getFactionAt(loc);

        // no door/chest/whatever protection in wilderness, war zones, or safe zones
        if (!otherFaction.isNormal()) {
            return true;
        }

        if (P.p.getConfig().getBoolean("hcf.raidable", false) && otherFaction.getLandRounded() >= otherFaction.getPowerRounded()) {
            return true;
        }

        // Dupe fix.
        Faction myFaction = me.getFaction();
        Relation rel = myFaction.getRelationTo(otherFaction);
        if (!rel.isMember() || !otherFaction.playerHasOwnershipRights(me, loc) && player.getItemInHand() != null) {
            switch (player.getItemInHand().getType()) {
                case CHEST:
                case SIGN_POST:
                case TRAPPED_CHEST:
                case SIGN:
                case WOOD_DOOR:
                case IRON_DOOR:
                    return false;
                default:
                    break;
            }
        }

        PermissableAction action = null;

        switch (block.getType()) {
            case LEVER:
                action = PermissableAction.LEVER;
                break;
            case STONE_BUTTON:
            case WOOD_BUTTON:
                action = PermissableAction.BUTTON;
                break;
            case DARK_OAK_DOOR:
            case ACACIA_DOOR:
            case BIRCH_DOOR:
            case IRON_DOOR:
            case JUNGLE_DOOR:
            case SPRUCE_DOOR:
            case TRAP_DOOR:
            case WOOD_DOOR:
            case WOODEN_DOOR:
                action = PermissableAction.DOOR;
                break;
            case CHEST:
            case ENDER_CHEST:
            case TRAPPED_CHEST:
                action = PermissableAction.CONTAINER;
                break;
            default:
                // Check for doors that might have diff material name in old version.
                if (block.getType().name().contains("DOOR")) {
                    action = PermissableAction.DOOR;
                }
                break;
        }

        // F PERM check runs through before other checks.
        Access access = otherFaction.getAccess(me, action);
        if (access == null || access == Access.DENY) {
            me.msg(TL.GENERIC_NOPERMISSION, action);
            return false;
        }

        // We only care about some material types.
        if (otherFaction.hasPlayersOnline()) {
            if (!Conf.territoryProtectedMaterials.contains(material)) {
                return true;
            }
        } else {
            if (!Conf.territoryProtectedMaterialsWhenOffline.contains(material)) {
                return true;
            }
        }

        // You may use any block unless it is another faction's territory...
        if (rel.isNeutral() || (rel.isEnemy() && Conf.territoryEnemyProtectMaterials) || (rel.isAlly() && Conf.territoryAllyProtectMaterials) || (rel.isTruce() && Conf.territoryTruceProtectMaterials)) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_TERRITORY, (material == Material.SOIL ? "trample " : "use ") + TextUtil.getMaterialName(material), otherFaction.getTag(myFaction));
            }

            return false;
        }

        // Also cancel if player doesn't have ownership rights for this claim
        if (Conf.ownedAreasEnabled && Conf.ownedAreaProtectMaterials && !otherFaction.playerHasOwnershipRights(me, loc)) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_OWNED, TextUtil.getMaterialName(material), otherFaction.getOwnerListString(loc));
            }

            return false;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        FPlayer me = FPlayers.getInstance().getByPlayer(event.getPlayer());

        me.getPower();  // update power, so they won't have gained any while dead

        Location home = me.getFaction().getHome();
        if (Conf.homesEnabled &&
                Conf.homesTeleportToOnDeath &&
                home != null &&
                (Conf.homesRespawnFromNoPowerLossWorlds || !Conf.worldsNoPowerLoss.contains(event.getPlayer().getWorld().getName()))) {
            event.setRespawnLocation(home);
        }
    }

    // For some reason onPlayerInteract() sometimes misses bucket events depending on distance (something like 2-3 blocks away isn't detected),
    // but these separate bucket events below always fire without fail
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlockClicked();
        Player player = event.getPlayer();

        if (!playerCanUseItemHere(player, block.getLocation(), event.getBucket(), false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlockClicked();
        Player player = event.getPlayer();

        if (!playerCanUseItemHere(player, block.getLocation(), event.getBucket(), false)) {
            event.setCancelled(true);
        }
    }

    public static boolean preventCommand(String fullCmd, Player player) {
        if ((Conf.territoryNeutralDenyCommands.isEmpty() && Conf.territoryEnemyDenyCommands.isEmpty() && Conf.permanentFactionMemberDenyCommands.isEmpty() && Conf.warzoneDenyCommands.isEmpty())) {
            return false;
        }

        fullCmd = fullCmd.toLowerCase();

        FPlayer me = FPlayers.getInstance().getByPlayer(player);

        String shortCmd;  // command without the slash at the beginning
        if (fullCmd.startsWith("/")) {
            shortCmd = fullCmd.substring(1);
        } else {
            shortCmd = fullCmd;
            fullCmd = "/" + fullCmd;
        }

        if (me.hasFaction() &&
                !me.isAdminBypassing() &&
                !Conf.permanentFactionMemberDenyCommands.isEmpty() &&
                me.getFaction().isPermanent() &&
                isCommandInList(fullCmd, shortCmd, Conf.permanentFactionMemberDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_PERMANENT, fullCmd);
            return true;
        }

        Faction at = Board.getInstance().getFactionAt(new FLocation(player.getLocation()));
        if (at.isWilderness() && !Conf.wildernessDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.wildernessDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_WILDERNESS, fullCmd);
            return true;
        }

        Relation rel = at.getRelationTo(me);
        if (at.isNormal() && rel.isAlly() && !Conf.territoryAllyDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.territoryAllyDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_ALLY, fullCmd);
            return false;
        }

        if (at.isNormal() && rel.isNeutral() && !Conf.territoryNeutralDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.territoryNeutralDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_NEUTRAL, fullCmd);
            return true;
        }

        if (at.isNormal() && rel.isEnemy() && !Conf.territoryEnemyDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.territoryEnemyDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_ENEMY, fullCmd);
            return true;
        }

        if (at.isWarZone() && !Conf.warzoneDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.warzoneDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_WARZONE, fullCmd);
            return true;
        }

        return false;
    }

    private static boolean isCommandInList(String fullCmd, String shortCmd, Iterator<String> iter) {
        String cmdCheck;
        while (iter.hasNext()) {
            cmdCheck = iter.next();
            if (cmdCheck == null) {
                iter.remove();
                continue;
            }

            cmdCheck = cmdCheck.toLowerCase();
            if (fullCmd.startsWith(cmdCheck) || shortCmd.startsWith(cmdCheck)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractGUI(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) {
            return;
        }
        if (event.getClickedInventory().getHolder() instanceof FactionGUI) {
            event.setCancelled(true);
            ((FactionGUI) event.getClickedInventory().getHolder()).onClick(event.getRawSlot(), event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMoveGUI(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof FactionGUI) {
            event.setCancelled(true);
        }
    }


    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        FPlayer badGuy = FPlayers.getInstance().getByPlayer(event.getPlayer());
        if (badGuy == null) {
            return;
        }

        // if player was banned (not just kicked), get rid of their stored info
        if (Conf.removePlayerDataWhenBanned && event.getReason().equals("Banned by admin.")) {
            if (badGuy.getRole() == Role.ADMIN) {
                badGuy.getFaction().promoteNewLeader();
            }

            badGuy.leave(false);
            badGuy.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    final public void onFactionJoin(FPlayerJoinEvent event) {
        FTeamWrapper.applyUpdatesLater(event.getFaction());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFactionLeave(FPlayerLeaveEvent event) {
        FTeamWrapper.applyUpdatesLater(event.getFaction());
    }
}
