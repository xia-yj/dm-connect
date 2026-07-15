package dm.jdbc.driver;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

public final class DmDriver implements Driver {
    @Override
    public Connection connect(String url, Properties info) {
        return null;
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:dm:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 8;
    }

    @Override
    public int getMinorVersion() {
        return 1;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }
}
