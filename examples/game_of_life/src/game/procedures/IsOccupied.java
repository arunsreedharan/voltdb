/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package game.procedures;

import org.voltdb.*;

@ProcInfo
(
    singlePartition = true,
    partitionInfo = "cells.cell_id: 0"
)
public class IsOccupied extends VoltProcedure
{
    public static final SQLStmt SQLquery = new SQLStmt
    (
        "SELECT occupied " +
        "FROM cells " +
        "WHERE cell_id = ?;"
    );

    //returns 1 if cell now alive, 0 if cell now dead
    public long run(int id)
        throws VoltAbortException
    {
        voltQueueSQL(SQLquery, id);

        VoltTable[] tables = voltExecuteSQL();
        assert(tables.length == 1);

        VoltTable table = tables[0];
        assert(table.getRowCount() == 1 && table.getColumnCount() == 1);

        VoltTableRow row = table.fetchRow(0);
        //assert type is TINYINT

        return row.getLong(0);
    }
}