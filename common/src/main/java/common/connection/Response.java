package common.connection;

import common.data.HumanBeing;

import java.io.Serializable;
import java.util.Collection;

public interface Response extends Serializable {

    String getMessage();

    Status getStatus();

    public Collection<HumanBeing> getCollection();

    enum Status {
        ERROR,
        FINE,
        EXIT,
        AUTH_SUCCESS,
        BROADCAST,
        COLLECTION
    }

    CollectionOperation getCollectionOperation();

}
