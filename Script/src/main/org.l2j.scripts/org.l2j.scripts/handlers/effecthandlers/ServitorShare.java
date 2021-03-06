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
package org.l2j.scripts.handlers.effecthandlers;

import org.l2j.gameserver.engine.skill.api.Skill;
import org.l2j.gameserver.engine.skill.api.SkillEffectFactory;
import org.l2j.gameserver.model.StatsSet;
import org.l2j.gameserver.model.actor.Creature;
import org.l2j.gameserver.model.actor.instance.Player;
import org.l2j.gameserver.model.effects.AbstractEffect;
import org.l2j.gameserver.model.stats.Stat;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import static java.util.Objects.nonNull;
import static org.l2j.gameserver.util.GameUtils.isSummon;

/**
 * Servitor Share effect implementation.
 * @author JoeAlisson
 */
public final class ServitorShare extends AbstractEffect {
    private final Map<Stat, Float> sharedStats;

    private ServitorShare(StatsSet params) {
        if(params.contains("type")) {
            sharedStats = Map.of(params.getEnum("type", Stat.class), params.getFloat("power") / 100);
        } else {
            sharedStats = new HashMap<>();
            params.getSet().forEach((key, value) -> {
                if(key.startsWith("stat")) {
                    var set = (StatsSet) value;
                    sharedStats.put(set.getEnum("type", Stat.class), set.getFloat("power") / 100);
                }
            });
        }
    }

    @Override
    public boolean canPump(Creature effector, Creature effected, Skill skill)
    {
        return isSummon(effected);
    }

    @Override
    public void pump(Creature effected, Skill skill) {
        final Player owner = effected.getActingPlayer();
        if (nonNull(owner)) {
            for (Entry<Stat, Float> stats : sharedStats.entrySet()) {
                effected.getStats().mergeAdd(stats.getKey(), owner.getStats().getValue(stats.getKey()) * stats.getValue());
            }
        }
    }

    public static class Factory implements SkillEffectFactory {

        @Override
        public AbstractEffect create(StatsSet data) {
            return new ServitorShare(data);
        }

        @Override
        public String effectName() {
            return "servitor-share";
        }
    }
}