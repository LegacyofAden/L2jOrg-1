/*
 * Copyright © 2019-2020 L2JOrg
 *
 * This file is part of the L2JOrg project.
 *
 * L2JOrg is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * L2JOrg is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2j.gameserver.engine.olympiad;

import io.github.joealisson.primitive.HashIntMap;
import io.github.joealisson.primitive.IntMap;
import org.l2j.commons.threading.ThreadPool;
import org.l2j.commons.util.Rnd;
import org.l2j.commons.util.collection.LimitedQueue;
import org.l2j.gameserver.cache.HtmCache;
import org.l2j.gameserver.data.database.dao.OlympiadDAO;
import org.l2j.gameserver.data.database.data.OlympiadData;
import org.l2j.gameserver.data.database.data.OlympiadParticipantData;
import org.l2j.gameserver.data.database.data.OlympiadRankData;
import org.l2j.gameserver.instancemanager.AntiFeedManager;
import org.l2j.gameserver.instancemanager.InstanceManager;
import org.l2j.gameserver.model.actor.instance.Player;
import org.l2j.gameserver.model.eventengine.AbstractEventManager;
import org.l2j.gameserver.model.eventengine.ScheduleTarget;
import org.l2j.gameserver.model.events.EventType;
import org.l2j.gameserver.model.events.Listeners;
import org.l2j.gameserver.model.events.impl.character.player.OnPlayerLogin;
import org.l2j.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2j.gameserver.model.olympiad.OlympiadResultInfo;
import org.l2j.gameserver.network.SystemMessageId;
import org.l2j.gameserver.network.serverpackets.html.NpcHtmlMessage;
import org.l2j.gameserver.network.serverpackets.olympiad.*;
import org.l2j.gameserver.settings.ServerSettings;
import org.l2j.gameserver.util.Broadcast;
import org.l2j.gameserver.util.GameXmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.l2j.commons.configuration.Configurator.getSettings;
import static org.l2j.commons.database.DatabaseAccess.getDAO;
import static org.l2j.gameserver.engine.olympiad.OlympiadState.*;
import static org.l2j.gameserver.network.SystemMessageId.*;
import static org.l2j.gameserver.network.serverpackets.SystemMessage.getSystemMessage;

/**
 * @author JoeAlisson
 */
public class Olympiad extends AbstractEventManager<OlympiadMatch> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Olympiad.class);

    private final Set<OlympiadMatch> matches = ConcurrentHashMap.newKeySet(10);
    private final IntMap<OlympiadParticipantData> participantsData = new HashIntMap<>();
    private final IntMap<LimitedQueue<OlympiadBattleRecord>> recentBattlesRecord = new HashIntMap<>();

    private Set<Player> registered = ConcurrentHashMap.newKeySet(20);
    private Set<Player> matchMaking = ConcurrentHashMap.newKeySet(20);

    private ConsumerEventListener onPlayerLoginListener;
    private OlympiadData data = new OlympiadData();
    private OlympiadState state = OlympiadState.SCHEDULED;
    private LocalDate startDate;
    private Duration matchDuration;

    private boolean forceStartDate;
    private int minParticipant;
    private int[] availableArenas;
    private short initialPoints;
    private short maxBattlesPerDay;
    private short minLoserPoints;
    private short maxLoserPoints;
    private short minWinnerPoints;
    private short maxWinnerPoints;
    private boolean transferPoints;

    private Olympiad() {
    }

    @Override
    public void config(GameXmlReader reader, Node configNode) {
        final var olympiadConfig= configNode.getFirstChild();
        if(nonNull(olympiadConfig) && olympiadConfig.getNodeName().equals("olympiad-config")) {
            final var attr = olympiadConfig.getAttributes();
            minParticipant = reader.parseInt(attr, "min-participant");
            forceStartDate = reader.parseBoolean(attr, "force-start-date");
            String strDate = reader.parseString(attr, "start-date");
            startDate = isNull(strDate) ? LocalDate.now() : LocalDate.parse(strDate);
            availableArenas = reader.parseIntArray(attr, "available-arena-instances");
            matchDuration = Duration.ofMinutes(reader.parseInt(attr, "match-duration"));
            initialPoints = reader.parseShort(attr, "initial-points");
            minLoserPoints = reader.parseShort(attr, "min-loser-points");
            maxLoserPoints = reader.parseShort(attr, "max-loser-points");
            minWinnerPoints = reader.parseShort(attr, "min-winner-points");
            maxWinnerPoints = reader.parseShort(attr, "max-winner-points");
            transferPoints = reader.parseBoolean(attr, "transfer-points");
            maxBattlesPerDay = reader.parseShort(attr, "max-battles-per-day");
        }
    }

    @Override
    public void onInitialized() {
        data = getDAO(OlympiadDAO.class).findData();

        if(isNull(data)) {
            data = new OlympiadData();
            data.setNextSeasonDate(startDate);
            data.setId(1);
            getDAO(OlympiadDAO.class).save(data);
        } else if(forceStartDate || isNull(data.getNextSeasonDate())) {
            data.setNextSeasonDate(startDate);
        }

        if(data.getNextSeasonDate().isAfter(LocalDate.now())) {
            LOGGER.info("World Olympiad season start scheduled to {}", data.getNextSeasonDate());
        } else if(data.getSeason() > 0) {
            LOGGER.info("World Olympiad {} season has been started", data.getSeason());
        }

        AntiFeedManager.getInstance().registerEvent(AntiFeedManager.OLYMPIAD_ID);
    }

    @ScheduleTarget
    public void onStartMatch() {
        if(data.getNextSeasonDate().isAfter(LocalDate.now()) || data.getSeason() < 1) {
            return;
        }

        var scheduler = getScheduler("stop-match");
        if(nonNull(scheduler)) {
            state = STARTED;
            var listeners = Listeners.players();
            onPlayerLoginListener = new ConsumerEventListener(listeners, EventType.ON_PLAYER_LOGIN, (Consumer<OnPlayerLogin>) this::onPlayerLogin, this);
            listeners.addListener(onPlayerLoginListener);
            Broadcast.toAllOnlinePlayers(ExOlympiadInfo.show(OlympiadRuleType.CLASSLESS, (int) scheduler.getRemainingTime(TimeUnit.SECONDS)));
            Broadcast.toAllOnlinePlayers(getSystemMessage(SystemMessageId.SHARPEN_YOUR_SWORDS_TIGHTEN_THE_STITCHING_IN_YOUR_ARMOR_AND_MAKE_HASTE_TO_A_OLYMPIAD_MANAGER_BATTLES_IN_THE_OLYMPIAD_GAMES_ARE_NOW_TAKING_PLACE));
        } else {
            LOGGER.warn("Can't find stop-match scheduler");
        }
    }

    private void onPlayerLogin(OnPlayerLogin event) {
        if(state.matchesInProgress()) {
            var scheduler = getScheduler("stop-match");
            if(nonNull(scheduler)) {
                event.getPlayer().sendPacket(ExOlympiadInfo.show(OlympiadRuleType.CLASSLESS, (int) scheduler.getRemainingTime(TimeUnit.SECONDS)));
            } else {
                LOGGER.warn("Can't find stop-match scheduler");
            }
        }
    }

    @ScheduleTarget
    public void onStopMatch() {
        if(state.matchesInProgress()) {
            return;
        }
        state = SCHEDULED;
        if(nonNull(onPlayerLoginListener)) {
            onPlayerLoginListener.unregisterMe();
        }
        Broadcast.toAllOnlinePlayers(ExOlympiadInfo.hide(OlympiadRuleType.CLASSLESS));
        Broadcast.toAllOnlinePlayers(getSystemMessage(SystemMessageId.THE_OLYMPIAD_REGISTRATION_PERIOD_HAS_ENDED));
        matchMaking.clear();
        registered.clear();
        updateParticipantsData();
    }

    private void updateParticipantsData() {
        if(matches.isEmpty()) {
            var participants = participantsData.values();
            participants.forEach(OlympiadParticipantData::resetBattlesToday);
            getDAO(OlympiadDAO.class).save(participants);
        } else {
            ThreadPool.schedule(this::updateParticipantsData, 1, TimeUnit.MINUTES);
        }
    }

    @ScheduleTarget
    public void onNewSeason(){
        if(LocalDate.now().compareTo(data.getNextSeasonDate()) >= 0) {
            data.increaseSeason();
            getDAO(OlympiadDAO.class).save(data);
            Broadcast.toAllOnlinePlayers(getSystemMessage(SystemMessageId.ROUND_S1_OF_THE_OLYMPIAD_GAMES_HAS_STARTED).addInt(data.getSeason()));
        }
    }

    public boolean isMatchesInProgress() {
        return state.matchesInProgress();
    }

    public int getCurrentSeason() {
        return data.getSeason();
    }

    public int getPeriod() {
        return data.getPeriod();
    }

    public void saveOlympiadStatus() {
        getDAO(OlympiadDAO.class).save(data);
    }

    public short getOlympiadPoints(Player player) {
        return participantDataOf(player).getPoints();
    }

    public int getRemainingDailyMatches(Player player) {
        return maxBattlesPerDay - participantDataOf(player).getBattlesToday();
    }

    public short getMaxBattlesPerDay() {
        return maxBattlesPerDay;
    }

    public void requestMatchMaking(Player player) {
        if (!validateMatchMaking(player)) {
            return;
        }

        var htmlMessage = new NpcHtmlMessage(HtmCache.getInstance().getHtm(player, "data/html/olympiad/match-making.htm"));
        htmlMessage.replace("%stats%", stats());
        player.sendPacket(htmlMessage);
    }

    private boolean validateMatchMaking(Player player) {
        if(!state.matchesInProgress())  {
            return false;
        }

        if(player.isInOlympiadMode() || registered.contains(player)) {
            player.sendPacket(getSystemMessage(C1_YOU_HAVE_ALREADY_REGISTERED_FOR_THE_MATCH).addPcName(player));
            return false;
        }

        if(getOlympiadPoints(player) <= 0) {

            return false;
        }

        if(!player.isInventoryUnder80()) {
            player.sendPacket(getSystemMessage(C1_DOES_NOT_MEET_THE_PARTICIPATION_REQUIREMENTS_FOR_OLYMPIAD_AS_THE_INVENTORY_WEIGHT_SLOT_EXCEEDS_80).addPcName(player));
            return false;
        }

        return true;
    }

    private String stats() {
        return String.format("(period: %d, week: %d, number of participants: %d)", data.getPeriod(), data.getSeason(), registered.size() + matchMaking.size() + matches.size() * 2);
    }

    public void startMatchMaking(Player player) {
        if(!validateMatchMaking(player)) {
            return;
        }

        registered.add(player);
        player.sendPacket(YOU_VE_BEEN_REGISTERED_IN_THE_WAITING_LIST_OF_ALL_CLASS_BATTLE);
        showRecord(player);
        player.sendPackets(new ExOlympiadMatchMakingResult(true, OlympiadRuleType.CLASSLESS));

        if(state != MATCH_MAKING && registered.size() >= minParticipant) {
            state = MATCH_MAKING;
            ThreadPool.schedule(this::distributeAndStartMatches, 1, TimeUnit.MINUTES);
        }
    }

    private void distributeAndStartMatches() {
        var tmp = matchMaking;
        matchMaking = registered;
        registered = tmp;

        var participants = matchMaking.iterator();
        while(matchMaking.size() >= OlympiadRuleType.CLASSLESS.participantCount()) {
            newMatch(participants);
        }

        if(!matchMaking.isEmpty()) {
            registered.addAll(matchMaking);
            matchMaking.clear();
        }

        if(state == MATCH_MAKING) {
            state = STARTED;
        }
    }

    private void newMatch(Iterator<Player> participants) {
        OlympiadMatch match = OlympiadMatch.of(OlympiadRuleType.CLASSLESS);
        var arena = InstanceManager.getInstance().createInstance(Rnd.get(availableArenas));
        match.setArenaInstance(arena);
        match.setMatchDuration(matchDuration);
        for (int i = 0; i < OlympiadRuleType.CLASSLESS.participantCount(); i++) {
            var participant = participants.next();
            match.addParticipant(participant);
            participants.remove();
            participantDataOf(participant).increaseBattlesToday();
        }

        matches.add(match);
        ThreadPool.schedule(match, 10, TimeUnit.SECONDS);
    }

    void finishMatch(OlympiadMatch olympiadMatch) {
        matches.remove(olympiadMatch);
    }

    public void unregisterPlayer(Player player, OlympiadRuleType ruleType) {
        if(registered.remove(player)) {
            player.sendPacket(YOU_HAVE_BEEN_REMOVED_FROM_THE_OLYMPIAD_WAITING_LIST);
            player.sendPacket(new ExOlympiadMatchMakingResult(false, ruleType));
            showRecord(player);
        }
    }

    public boolean isRegistered(Player player) {
        return player.isInOlympiadMode() || registered.contains(player);
    }

    @Override
    protected void onPlayerLogout(Player player) {
        unregisterPlayer(player, OlympiadRuleType.CLASSLESS);
    }

    public int getSeasonMonth() {
        return YearMonth.now().getMonthValue();
    }

    public int getSeasonYear() {
        return Year.now().getValue();
    }

    public Collection<OlympiadMatch> getMatches() {
        return matches;
    }

    public void showRecord(Player player) {
        player.sendPacket(new ExOlympiadRecord(participantDataOf(player), null));
    }

    private OlympiadParticipantData participantDataOf(Player player) {
        var data = participantsData.get(player.getObjectId());
        if(isNull(data)) {
            data = loadOrCreateParticipantData(player);
        }
        return data;
    }

    private OlympiadParticipantData loadOrCreateParticipantData(Player player) {
        OlympiadParticipantData data;
        data = getDAO(OlympiadDAO.class).findParticipantData(player.getObjectId(), getSettings(ServerSettings.class).serverId());
        if(isNull(data)) {
            data = OlympiadParticipantData.of(player, initialPoints, getSettings(ServerSettings.class).serverId());
            getDAO(OlympiadDAO.class).save(data);
        }
        participantsData.put(player.getObjectId(), data);
        return data;
    }

    short updateDefeat(Player player, int points, OlympiadResultInfo winnerLeader) {
        var data = participantDataOf(player);
        data.updatePoints(points);
        data.increaseDefeats();
        getDAO(OlympiadDAO.class).updateDefeat(player.getObjectId(), getSettings(ServerSettings.class).serverId(), data.getPoints(), data.getBattlesLost());
        recentBattlesRecord.computeIfAbsent(player.getObjectId(), i -> new LimitedQueue<>(3)).add(new OlympiadBattleRecord(winnerLeader.getName(), winnerLeader.getClassId(), winnerLeader.getLevel(), true));
        return data.getPoints();
    }

    short updateVictory(Player player, int points, OlympiadResultInfo loserLeader) {
        var data = participantDataOf(player);
        data.updatePoints(points);
        data.increaseVictory();
        getDAO(OlympiadDAO.class).updateVictory(player.getObjectId(), getSettings(ServerSettings.class).serverId(), data.getPoints(), data.getBattlesWon());
        recentBattlesRecord.computeIfAbsent(player.getObjectId(), i -> new LimitedQueue<>(3)).add(new OlympiadBattleRecord(loserLeader.getName(), loserLeader.getClassId(), loserLeader.getLevel(), false));
        return data.getPoints();
    }

    int getRandomLoserPoints() {
        return Rnd.get(minLoserPoints, maxLoserPoints);
    }

    int getRandomWinnerPoints() {
        return Rnd.get(minWinnerPoints, maxWinnerPoints);
    }

    boolean isPointTransfer() {
        return transferPoints;
    }

    public void showPersonalRank(Player player) {
        final var rankData = getDAO(OlympiadDAO.class).findRankData(player.getObjectId(), getSettings(ServerSettings.class).serverId());
        final var previousData = getDAO(OlympiadDAO.class).findPreviousRankData(player.getObjectId(), getSettings(ServerSettings.class).serverId());
        player.sendPacket(new ExOlympiadMyRankInfo(rankData, previousData, recentBattlesRecord.get(player.getObjectId())));
    }

    public void showRanking(Player player, byte type, byte scope, boolean currentSeason, int classId, int server) {
        final List<OlympiadRankData> data = currentSeason ? getCurrentRankers(player, type, scope, classId, server) : getPreviousRankers(player, type, scope, classId, server);
        final int participants = currentSeason ? getDAO(OlympiadDAO.class).countParticipants() : getDAO(OlympiadDAO.class).countPreviousParticipants();
        player.sendPacket(new ExOlympiadRankingInfo(type, scope, currentSeason, classId, server, data, participants));
    }

    private List<OlympiadRankData> getPreviousRankers(Player player, byte type, byte scope, int classId, int server) {
        final var dao = getDAO(OlympiadDAO.class);
        if(type == 1) {
            return scope == 1 ? dao.findPreviousRankersNextToPlayerByClass(player.getObjectId(), classId) : dao.findPreviousRankersByClass(classId, server);
        }
        return scope == 1 ? dao.findPreviousRankersNextToPlayer(player.getObjectId()) : dao.findPreviousRankers();
    }

    private List<OlympiadRankData> getCurrentRankers(Player player, byte type, byte scope, int classId, int server) {
        final var dao = getDAO(OlympiadDAO.class);

        if(type == 1) {
            return scope == 1 ? dao.findRankersNextToPlayerByClass(player.getObjectId(), classId) : dao.findRankersByClass(classId, server);
        }

        return scope == 1 ? dao.findRankersNextToPlayer(player.getObjectId()) : dao.findRankers();
    }

    public static Olympiad getInstance() {
        return Singleton.INSTANCE;
    }

    private static class Singleton {
        private static final Olympiad INSTANCE = new Olympiad();
    }

}
