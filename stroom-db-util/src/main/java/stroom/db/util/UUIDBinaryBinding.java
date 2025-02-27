import stroom.db.util.UUIDBinaryConverter;

import com.google.gson.Gson;
import org.jooq.Binding;
import org.jooq.BindingGetResultSetContext;
import org.jooq.BindingGetSQLInputContext;
import org.jooq.BindingGetStatementContext;
import org.jooq.BindingRegisterContext;
import org.jooq.BindingSQLContext;
import org.jooq.BindingSetSQLOutputContext;
import org.jooq.BindingSetStatementContext;
import org.jooq.Converter;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.UUID;

// We're binding <T> = byte[] (or byte[]B), and <U> = UUID (user type)
// Alternatively, extend org.jooq.impl.AbstractBinding to implement fewer methods.
public class UUIDBinaryBinding implements Binding<byte[], UUID> {

    private static final UUIDBinaryConverter CONVERTER = new UUIDBinaryConverter();

    private final Gson gson = new Gson();

    // The converter does all the work
    @Override
    public Converter<byte[], UUID> converter() {
        return CONVERTER;
    }

    // Rending a bind variable for the binding context's value and casting it to the uuid type
    @Override
    public void sql(BindingSQLContext<UUID> ctx) throws SQLException {
        // Depending on how you generate your SQL, you may need to explicitly distinguish
        // between jOOQ generating bind variables or inlined literals.
        if (ctx.render().paramType() == ParamType.INLINED) {
            ctx.render().visit(DSL.inline(ctx.convert(converter()).value())).sql("::uuid");
        } else {
            ctx.render().sql(ctx.variable()).sql("::uuid");
        }
    }

    // Registering VARCHAR types for JDBC CallableStatement OUT parameters
    @Override
    public void register(BindingRegisterContext<UUID> ctx) throws SQLException {
        ctx.statement().registerOutParameter(ctx.index(), Types.BINARY);
    }

    // Converting the UUID to a String value and setting that on a JDBC PreparedStatement
    @Override
    public void set(BindingSetStatementContext<UUID> ctx) throws SQLException {
        final byte[] bytes = ctx.convert(converter()).value();
        ctx.statement().setBytes(ctx.index(), bytes);
    }

    // Getting a String value from a JDBC ResultSet and converting that to a UUID
    @Override
    public void get(BindingGetResultSetContext<UUID> ctx) throws SQLException {
        ctx.convert(converter()).value(ctx.resultSet().getBytes(ctx.index()));
    }

    // Getting a String value from a JDBC CallableStatement and converting that to a UUID
    @Override
    public void get(BindingGetStatementContext<UUID> ctx) throws SQLException {
        ctx.convert(converter()).value(ctx.statement().getBytes(ctx.index()));
    }

    // Setting a value on a JDBC SQLOutput (useful for Oracle OBJECT types)
    @Override
    public void set(BindingSetSQLOutputContext<UUID> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    // Getting a value from a JDBC SQLInput (useful for Oracle OBJECT types)
    @Override
    public void get(BindingGetSQLInputContext<UUID> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }
}
