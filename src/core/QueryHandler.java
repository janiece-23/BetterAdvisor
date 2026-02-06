package core;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface QueryHandler<T> {
    T handle(ResultSet rs) throws SQLException;
}
