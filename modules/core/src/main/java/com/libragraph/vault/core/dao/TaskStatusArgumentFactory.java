package com.libragraph.vault.core.dao;

import com.libragraph.vault.core.task.TaskStatus;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class TaskStatusArgumentFactory extends AbstractArgumentFactory<TaskStatus> {

    public TaskStatusArgumentFactory() {
        super(Types.SMALLINT);
    }

    @Override
    protected Argument build(TaskStatus value, ConfigRegistry config) {
        return (position, statement, ctx) -> statement.setShort(position, (short) value.id());
    }
}
