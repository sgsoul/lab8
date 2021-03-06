package common.connection;

import common.auth.User;
import common.data.HumanBeing;

import java.io.Serializable;
import java.net.InetSocketAddress;


public interface Request extends Serializable {
    String getStringArg();

    HumanBeing getHuman();

    String getCommandName();

    User getUser();

    Request setUser(User usr);

    Status getStatus();

    Request setStatus(Status s);

    InetSocketAddress getBroadcastAddress();

    Request setBroadcastAddress(InetSocketAddress address);

    enum Status {
        HELLO,
        DEFAULT,
        CONNECTION_TEST,
        SENT_FROM_CLIENT,
        RECEIVED_BY_SERVER,
        EXIT
    }
}
