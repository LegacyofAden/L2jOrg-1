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

import org.l2j.gameserver.engine.skill.api.SkillEffectFactory;
import org.l2j.gameserver.model.StatsSet;
import org.l2j.gameserver.model.effects.AbstractEffect;
import org.l2j.gameserver.model.effects.EffectFlag;

/**
 * Effect that blocks all incoming debuffs.
 * @author Nik
 * @author JoeAlisson
 */
public final class DebuffBlock extends AbstractEffect {
    private DebuffBlock() {
    }

    @Override
    public long getEffectFlags()
    {
        return EffectFlag.DEBUFF_BLOCK.getMask();
    }

    public static class Factory implements SkillEffectFactory {
        private static final DebuffBlock INSTANCE = new DebuffBlock();

        @Override
        public AbstractEffect create(StatsSet data) {
            return INSTANCE ;
        }

        @Override
        public String effectName() {
            return "DebuffBlock";
        }
    }
}
