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
package org.l2j.gameserver.network.clientpackets.castle;

import org.l2j.gameserver.instancemanager.CastleManager;
import org.l2j.gameserver.network.clientpackets.ClientPacket;
import org.l2j.gameserver.network.serverpackets.siege.ExMercenaryCastleWarCastleInfo;

import static java.util.Objects.nonNull;

public class ExCastleWarInfo extends ClientPacket {

    private int castleId;

    @Override
    protected void readImpl() throws Exception {
        castleId = readInt();
    }

    @Override
    protected void runImpl() {
        final var castle = CastleManager.getInstance().getCastleById(castleId);
        if(nonNull(castle)) {
            client.sendPacket(new ExMercenaryCastleWarCastleInfo(castle));
        }
    }
}
