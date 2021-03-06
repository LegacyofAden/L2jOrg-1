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
package org.l2j.gameserver.network.serverpackets;

import io.github.joealisson.mmocore.WritableBuffer;
import org.l2j.gameserver.model.Clan;
import org.l2j.gameserver.network.GameClient;
import org.l2j.gameserver.network.ServerPacketId;

public final class PledgeStatusChanged extends ServerPacket {
    private final Clan _clan;

    public PledgeStatusChanged(Clan clan) {
        _clan = clan;
    }

    @Override
    public void writeImpl(GameClient client, WritableBuffer buffer) {
        writeId(ServerPacketId.PLEDGE_STATUS_CHANGED, buffer );

        buffer.writeInt(0x00);
        buffer.writeInt(_clan.getLeaderId());
        buffer.writeInt(_clan.getId());
        buffer.writeInt(_clan.getCrestId());
        buffer.writeInt(_clan.getAllyId());
        buffer.writeInt(_clan.getAllyCrestId());
        buffer.writeInt(_clan.getCrestLargeId());
        buffer.writeInt(0x00); // pledge type ?
    }

}
