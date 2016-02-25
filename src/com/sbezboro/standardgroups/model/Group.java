package com.sbezboro.standardgroups.model;

import com.sbezboro.standardgroups.StandardGroups;
import com.sbezboro.standardgroups.managers.GroupManager;
import com.sbezboro.standardplugin.StandardPlugin;
import com.sbezboro.standardplugin.model.StandardPlayer;
import com.sbezboro.standardplugin.persistence.PersistedListProperty;
import com.sbezboro.standardplugin.persistence.PersistedObject;
import com.sbezboro.standardplugin.persistence.PersistedProperty;
import com.sbezboro.standardplugin.persistence.storages.FileStorage;
import com.sbezboro.standardplugin.util.MiscUtil;
import org.bukkit.ChatColor;
import org.bukkit.Location;

import java.util.*;

public class Group extends PersistedObject implements Comparable<Group> {
	public static final String SAFE_AREA = "safearea";
	public static final String NEUTRAL_AREA = "neutralarea";

	private PersistedListProperty<String> memberUuids;
	private PersistedListProperty<String> moderatorUuids;
	private PersistedListProperty<String> invites;
	private PersistedListProperty<Claim> claims;
	private PersistedListProperty<Lock> locks;
	private PersistedListProperty<String> chatPlayerUuids;
	private PersistedListProperty<String> friendGroupUids;

	private PersistedProperty<String> uid;
	private PersistedProperty<Long> established;
	private PersistedProperty<Long> lastGrowth;
	private PersistedProperty<Integer> maxClaims;
	private PersistedProperty<Double> power;
	private PersistedProperty<Double> maxPower;
	private PersistedProperty<String> leaderUuid;

	private Map<String, Claim> locationToClaimMap;
	private Map<String, Integer> chunkToLockCountMap;
	private Map<String, Lock> locationToLockMap;
	private List<Group> groupsThatFriend;

	public Group(FileStorage storage, String name) {
		super(storage, name);

		initialize();
	}
	
	public Group(FileStorage storage, String name, long established, StandardPlayer leader) {
		super(storage, name);

		this.established.setValue(established);
		this.maxClaims.setValue(StandardGroups.getPlugin().getGroupStartingLand());
		this.power.setValue(20.0);
		this.maxPower.setValue(20.0);
		this.leaderUuid.setValue(leader.getUuidString());
		this.memberUuids.add(leader.getUuidString());

		initialize();
	}

	public void initialize() {
		this.locationToLockMap = new HashMap<String, Lock>();
		this.chunkToLockCountMap = new HashMap<String, Integer>();
		this.locationToClaimMap = new HashMap<String, Claim>();
		this.groupsThatFriend = new ArrayList<Group>();
	}

	@Override
	public void createProperties() {
		memberUuids = createList(String.class, "member-uuids");
		moderatorUuids = createList(String.class, "moderator-uuids");
		invites = createList(String.class, "invites");
		claims = createList(Claim.class, "claims");
		locks = createList(Lock.class, "locks");
		chatPlayerUuids = createList(String.class, "chat-player-uuids");
		friendGroupUids = createList(String.class, "friend-group-ids");

		uid = createProperty(String.class, "uid");
		established = createProperty(Long.class, "established");
		lastGrowth = createProperty(Long.class, "last-growth");
		maxClaims = createProperty(Integer.class, "max-claims");
		power = createProperty(Double.class, "power");
		maxPower = createProperty(Double.class, "max-power");
		leaderUuid = createProperty(String.class, "leader-uuid");
	}

	@Override
	public void loadProperties() {
		super.loadProperties();

		initialize();

		ArrayList<Claim> claimsToRemove = new ArrayList<Claim>();

		for (Claim claim : claims) {
			if (locationToClaimMap.containsKey(claim.getLocationKey())) {
				StandardGroups.getPlugin().getLogger().severe("Duplicate claim for " + getName() + " - " + claim.getX() + ", " + claim.getZ());
				claimsToRemove.add(claim);
			} else {
				claim.setGroup(this);

				locationToClaimMap.put(claim.getLocationKey(), claim);
			}
		}

		for (Claim claim : claimsToRemove) {
			claims.remove(claim);
		}

		if (!claimsToRemove.isEmpty()) {
			this.save();
		}

		for (Lock lock : locks) {
			lock.setGroup(this);

			locationToLockMap.put(lock.getLocationKey(), lock);
			incrementLockCount(lock.getChunkKey());
		}

		if (uid.getValue() == null || uid.getValue().length() == 0) {
			uid.setValue(UUID.randomUUID().toString().replaceAll("-", ""));
		}

		try {
			if (lastGrowth.getValue() == 0) {
				lastGrowth.setValue(established.getValue());
			}
		} catch (ClassCastException e) {
			// ignore
		}
		
		if (power.getValue() == null) {
			power.setValue(20.0);
		}
		if (maxPower.getValue() == null) {
			recalculateMaxPower();
		}
	}

	public String getUid() {
		return uid.getValue();
	}

	public String getName() {
		return getIdentifier();
	}

	public String getNameWithRelation(StandardPlayer player) {
		return (player != null && isMember(player) ? ChatColor.GREEN : ChatColor.YELLOW) + getIdentifier();
	}

	public boolean isSafeArea() {
		return getName().equals(Group.SAFE_AREA);
	}

	public boolean isNeutralArea() {
		return getName().equals(Group.NEUTRAL_AREA);
	}
	
	public void addMember(StandardPlayer player) {
		memberUuids.add(player.getUuidString());
		
		recalculateMaxPower();
		
		this.save();
	}
	
	public void removeMember(StandardPlayer player) {
		memberUuids.remove(player.getUuidString());

		if (isModerator(player)) {
			moderatorUuids.remove(player.getUuidString());
		}
		
		recalculateMaxPower();
		
		this.save();
	}

	public boolean isLeader(StandardPlayer player) {
		return getLeaderUuid().equals(player.getUuidString());
	}

	public boolean isModerator(StandardPlayer player) {
		return moderatorUuids.contains(player.getUuidString());
	}
	
	public boolean isMember(StandardPlayer player) {
		return memberUuids.contains(player.getUuidString());
	}

	public String getLeaderUuid() {
		return leaderUuid.getValue();
	}
	
	public List<String> getMemberUuids() {
		return memberUuids.getList();
	}

	public List<String> getModeratorUuids() {
		return moderatorUuids.getList();
	}

	public int getOnlineCount() {
		int online = 0;

		for (StandardPlayer player : getPlayers()) {
			if (player.isOnline()) {
				online++;
			}
		}

		return online;
	}

	public int getNonAltOnlineCount() {
		int online = 0;

		for (StandardPlayer player : getPlayers()) {
			if (player.isOnline() && !player.hasTitle("Alt")) {
				online++;
			}
		}

		return online;
	}
	
	public List<StandardPlayer> getPlayers() {
		ArrayList<StandardPlayer> list = new ArrayList<StandardPlayer>();

		for (String uuid : memberUuids) {
			StandardPlayer player = StandardPlugin.getPlugin().getStandardPlayerByUUID(uuid);
			list.add(player);
		}
		
		return list;
	}
	
	public int getPlayerCount() {
		return memberUuids.getList().size();
	}
	
	public int getNonAltPlayerCount() {
		int count = 0;

		for (String uuid : memberUuids) {
			StandardPlayer player = StandardPlugin.getPlugin().getStandardPlayerByUUID(uuid);
			if (!player.hasTitle("Alt")) {
				count++;
			}
		}
		
		return count;
	}
	
	public List<Claim> getClaims() {
		return claims.getList();
	}

	public Claim claim(StandardPlayer player, Location location) {
		Claim claim = new Claim(player, location, this);
		claims.add(claim);

		locationToClaimMap.put(claim.getLocationKey(), claim);
		
		recalculateMaxPower();
		
		this.save();
		
		return claim;
	}
	
	public void unclaim(Claim claim) {
		for (Lock lock : new ArrayList<Lock>(getLocks())) {
			if (locationToClaimMap.get(Claim.getLocationKey(lock.getLocation())) == claim) {
				locks.remove(lock, false);

				locationToLockMap.remove(lock.getLocationKey());
			}
		}

		claims.remove(claim);
		chunkToLockCountMap.put(claim.getLocationKey(), 0);
		locationToClaimMap.remove(claim.getLocationKey());
		
		recalculateMaxPower();
		
		this.save();
	}

	public Claim getClaim(Location location) {
		return locationToClaimMap.get(Claim.getLocationKey(location));
	}

	public List<Lock> getLocks() {
		return locks.getList();
	}

	public Lock getLock(Location location) {
		return locationToLockMap.get(MiscUtil.getLocationKey(location));
	}

	public Lock lock(StandardPlayer player, Location location) {
		Lock lock = new Lock(player, location, this);
		locks.add(lock);

		locationToLockMap.put(lock.getLocationKey(), lock);
		incrementLockCount(lock.getChunkKey());
		
		recalculateMaxPower();

		this.save();

		return lock;
	}

	public void unlock(Lock lock) {
		locks.remove(lock);

		locationToLockMap.remove(lock.getLocationKey());
		decrementLockCount(lock.getChunkKey());
		
		recalculateMaxPower();

		this.save();
	}

	public boolean canLock(Location location) {
		Integer lockCount = chunkToLockCountMap.get(MiscUtil.getChunkKey(location.getChunk()));
		return lockCount == null || lockCount < StandardGroups.getPlugin().getMaxLocksPerChunk();
	}

	public void incrementLockCount(String chunkKey) {
		Integer oldValue = chunkToLockCountMap.get(chunkKey);

		if (oldValue == null) {
			oldValue = 0;
		}

		chunkToLockCountMap.put(chunkKey, oldValue + 1);
	}

	public void decrementLockCount(String chunkKey) {
		chunkToLockCountMap.put(chunkKey, chunkToLockCountMap.get(chunkKey) - 1);
	}

	public void clearLocks() {
		this.locationToLockMap.clear();
		this.chunkToLockCountMap.clear();
		this.locks.clear();
	}
	
	public boolean isInvited(StandardPlayer player) {
		return invites.contains(player.getName());
	}
	
	public void invite(String username) {
		invites.add(username);
		
		this.save();
	}
	
	public void removeInvite(String username) {
		invites.remove(username);
		
		this.save();
	}

	public void addModerator(StandardPlayer player) {
		moderatorUuids.add(player.getUuidString());

		this.save();
	}

	public void removeModerator(StandardPlayer player) {
		moderatorUuids.remove(player.getUuidString());

		this.save();
	}

	public void setLeader(StandardPlayer player) {
		leaderUuid.setValue(player.getUuidString());

		this.save();
	}

	public long getEstablished() {
		return established.getValue();
	}

	public long getNextGrowth() {
		return getLastGrowth() + StandardGroups.getPlugin().getGroupLandGrowthDays() * 86400000;
	}

	public void setLastGrowth(long time) {
		lastGrowth.setValue(time);
	}

	public long getLastGrowth() {
		return lastGrowth.getValue();
	}

	public void grow() {
		int growthAmount = StandardGroups.getPlugin().getGroupLandGrowth();
		int maxAmount = StandardGroups.getPlugin().getGroupLandGrowthLimit();

		int newAmount = Math.min(maxClaims.getValue() + growthAmount, maxAmount);

		maxClaims.setValue(newAmount);
	}

	public void rename(String name) {
		setIdentifier(name);
	}

	public int getMaxClaims() {
		if (isSafeArea() || isNeutralArea()) {
			return 999999;
		}
		return maxClaims.getValue();
	}

	public void setMaxClaims(int maxClaims) {
		this.maxClaims.setValue(maxClaims);
	}
	
	public double getPower() {
		return power.getValue();
	}
	
	public String getPowerRounded() {
		return String.format("%.2f", getPower());
	}
	
	public void addPower(double difference) {
		GroupManager groupManager = StandardGroups.getPlugin().getGroupManager();
		
		double oldAmount = getPower();
		
		double minAmount = StandardGroups.getPlugin().getGroupPowerMinValue();
		double maxAmount = Math.min(StandardGroups.getPlugin().getGroupPowerMaxValue(), getMaxPower());
		
		double newAmount = Math.max(oldAmount + difference, minAmount);
		newAmount = Math.min(newAmount, maxAmount);
		
		power.setValue(newAmount);
		
		if (oldAmount >= 0.0f && newAmount < 0.0f) {
			sendGroupMessage(ChatColor.RED + "Your group's power has fallen below 0.");
		}
		if (oldAmount >= -6.0f && newAmount < -6.0f) {
			sendGroupMessage(ChatColor.DARK_RED + "Your group's power is now getting dangerously low.");
		}
		if (oldAmount >= groupManager.LOCK_POWER_THRESHOLD && newAmount < groupManager.LOCK_POWER_THRESHOLD) {
			sendGroupMessage(ChatColor.DARK_RED + "The locks of your group have become breakable.");
		}
		if (oldAmount <= groupManager.LOCK_POWER_THRESHOLD && newAmount > groupManager.LOCK_POWER_THRESHOLD) {
			sendGroupMessage(ChatColor.YELLOW + "The locks of your group are now functional again.");
		}
		if (oldAmount <= 0.0f && newAmount > 0.0f) {
			sendGroupMessage(ChatColor.YELLOW + "Your group's power has returned to positive.");
		}
	}
	
	public void setPower(double power) {
		this.power.setValue(power);
	}
	
	public double getMaxPower() {
		return maxPower.getValue();
	}
	
	public String getMaxPowerRounded() {
		return String.format("%.2f", getMaxPower());
	}
	
	public void setMaxPower(double maxPower) {
		this.maxPower.setValue(maxPower);
		
		if (getPower() > maxPower) {
			power.setValue(maxPower);
		}
	}
	
	public void recalculateMaxPower() {
		double claimDifference = (double)(getMaxClaims() - StandardGroups.getPlugin().getGroupStartingLand());
		double maxClaimDifference = (double)(StandardGroups.getPlugin().getGroupLandGrowthLimit() - StandardGroups.getPlugin().getGroupStartingLand());
		double relTime = claimDifference / maxClaimDifference;
		double timePenalty = 7.0 - 7.0 * (1.0 - relTime) * (1.0 - relTime);
		
		double numMembers = (double)getPlayerCount();
		double numNonAltMembers = (double)getNonAltPlayerCount();
		double memberPenalty = (numMembers <= 1.0 ? 0.0 : ( 5.0 - 6.0/numMembers ));
		
		double numClaims = (double)(getClaims().size());
		double bonusClaimThreshold = Math.min(numNonAltMembers - 1.0, 6.0);
		double claimPenalty = (numClaims <= 2.0 + bonusClaimThreshold ? 0.0 : ( 4.0 - 49.0 / (12.0*(numClaims-bonusClaimThreshold)-22.0) ));
		
		double numLocks = (double)(getLocks().size());
		double bonusLockThreshold = Math.min(2.0f * numNonAltMembers - 2.0f, 10.0f);
		double lockPenalty = (numLocks <= 5.0 + bonusLockThreshold ? 0.0 : ( 4.0 - 21.0 / (4.0*(numLocks-bonusLockThreshold)-18.0) ));
		
		double newAmount = 20.0 - timePenalty - memberPenalty - claimPenalty - lockPenalty;
		
		double minAmount = StandardGroups.getPlugin().getGroupPowerMinValue();
		double maxAmount = StandardGroups.getPlugin().getGroupPowerMaxValue();
		
		newAmount = Math.max(newAmount, minAmount);
		newAmount = Math.min(newAmount, maxAmount);
		
		setMaxPower(newAmount);
	}

	public void addLockMember(Lock lock, StandardPlayer otherPlayer) {
		lock.addMember(otherPlayer);

		this.save();
	}

	public void removeLockMember(Lock lock, StandardPlayer otherPlayer) {
		lock.removeMember(otherPlayer);

		this.save();
	}

	public boolean togglePublicLock(Lock lock) {
		if (lock.isPublic()) {
			lock.setPublic(false);
		} else {
			lock.setPublic(true);
		}

		this.save();

		return lock.isPublic();
	}

	public void sendGroupMessage(String message) {
		sendGroupMessage(message, null);
	}

	public void sendGroupMessage(String message, StandardPlayer from) {
		for (StandardPlayer member : getPlayers()) {
			if (from != member && member.isOnline()) {
				member.sendMessage(message);
			}
		}
	}

	public boolean isGroupChat(StandardPlayer player) {
		return chatPlayerUuids.contains(player.getUuidString());
	}

	public boolean toggleChat(StandardPlayer player) {
		boolean result;

		if (isGroupChat(player)) {
			chatPlayerUuids.remove(player.getUuidString());
			result = false;
		} else {
			chatPlayerUuids.add(player.getUuidString());
			result = true;
		}

		this.save();

		return result;
	}

	public void removeFriendships() {
		// Remove friend status of other groups to this group
		for (Group group : groupsThatFriend) {
			group.getFriendedGroupUids().remove(getUid());
		}
	}

	public void setFriend(Group otherGroup) {
		friendGroupUids.add(otherGroup.getUid());
		otherGroup.addGroupThatFriends(this);
	}

	public void unfriend(Group otherGroup) {
		friendGroupUids.remove(otherGroup.getUid());
		otherGroup.removeGroupThatFriends(this);
	}

	public boolean isGroupFriended(Group otherGroup) {
		return friendGroupUids.contains(otherGroup.getUid());
	}

	public boolean isMutualFriendship(Group otherGroup) {
		return isGroupFriended(otherGroup) && otherGroup.isGroupFriended(this);
	}

	public List<String> getFriendedGroupUids() {
		return friendGroupUids.getList();
	}

	public List<Group> getFriendedGroups() {
		List<Group> groups = new ArrayList<Group>();
		GroupManager groupManager = StandardGroups.getPlugin().getGroupManager();

		for (String uid : getFriendedGroupUids()) {
			Group group = groupManager.getGroupByUid(uid);

			if (group == null) {
				StandardGroups.getPlugin().getLogger().severe("Group " + getName() + " still friends invalid group " + uid);
			} else {
				groups.add(group);
			}
		}

		return groups;
	}

	public List<Group> getMutuallyFriendlyGroups() {
		List<Group> groups = new ArrayList<Group>();

		for (Group group : getFriendedGroups()) {
			if (group.isGroupFriended(this)) {
				groups.add(group);
			}
		}

		return groups;
	}

	public List<String> getMutuallyFriendedGroupUids() {
		List<String> mutuallyFriendedGroupUids = new ArrayList<String>();

		for (Group group : getMutuallyFriendlyGroups()) {
			mutuallyFriendedGroupUids.add(group.getUid());
		}

		return mutuallyFriendedGroupUids;
	}

	public void addGroupThatFriends(Group group) {
		groupsThatFriend.add(group);
	}

	public void removeGroupThatFriends(Group group) {
		groupsThatFriend.remove(group);
	}

	@Override
	public int compareTo(Group other) {
		if (getMemberUuids().size() == other.getMemberUuids().size()) {
			return getName().compareTo(other.getName());
		} else {
			return other.getMemberUuids().size() - getMemberUuids().size();
		}
	}

	public Map<String, Object> getInfo() {
		HashMap<String, Object> info = new HashMap<String, Object>();

		info.put("uid", getUid());
		info.put("name", getName());
		info.put("established", getEstablished());
		info.put("land_count", getClaims().size());
		info.put("land_limit", getMaxClaims());
		info.put("lock_count", getLocks().size());
		info.put("power", getPower());
		info.put("max_power", getMaxPower());

		info.put("invites", invites.getList());

		info.put("leader_uuid", leaderUuid.getValue());
		info.put("moderator_uuids", getModeratorUuids());
		info.put("member_uuids", getMemberUuids());

		info.put("friendly_group_uids", getMutuallyFriendedGroupUids());

		return info;
	}
}
