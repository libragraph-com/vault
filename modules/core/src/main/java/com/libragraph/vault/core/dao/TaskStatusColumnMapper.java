package com.libragraph.vault.core.dao;

import com.libragraph.vault.core.task.TaskStatus;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class TaskStatusColumnMapper implements ColumnMapper<TaskStatus> {

    @Override
    public TaskStatus map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return TaskStatus.fromId(r.getShort(columnNumber));
    }
}
