package database;

import auth.UserManager;
import collection.HumanCollectionManager;
import common.auth.User;
import common.data.Car;
import common.data.Coordinates;
import common.data.HumanBeing;
import common.data.WeaponType;
import common.exceptions.*;
import common.utils.DateConverter;
import exceptions.DataBaseException;
import log.Log;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;


public class HumanDBManager extends HumanCollectionManager {
    //language=SQL
    private final static String INSERT_HUMANS_QUERY = "INSERT INTO HUMANS (name, coordinates_x, coordinates_y, creation_date, real_hero, has_toothpick, impact_speed, soundtrack_name, minutes_of_waiting, weapon_type, car_name, user_login,id)" +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,DEFAULT) RETURNING id; ";
    private final DBManager dbManager;
    private final UserManager userManager;

    public HumanDBManager(DBManager c, UserManager userManager) throws DataBaseException {
        super();
        dbManager = c;
        this.userManager = userManager;
        create();
    }

    private void create() throws DataBaseException {
        //language=SQL
        String create =
                "CREATE TABLE IF NOT EXISTS HUMANS (" +
                        "id SERIAL PRIMARY KEY CHECK ( id > 0 )," +
                        "name TEXT NOT NULL CHECK (name <> '')," +
                        "coordinates_x FLOAT NOT NULL ," +
                        "coordinates_y FLOAT NOT NULL ," +
                        "creation_date TEXT NOT NULL," +
                        "real_hero BOOLEAN," +
                        "has_toothpick BOOLEAN," +
                        "impact_speed INTEGER ," +
                        "soundtrack_name TEXT NOT NULL CHECK (soundtrack_name <> '')," +
                        "minutes_of_waiting FLOAT NOT NULL," +
                        "weapon_type TEXT NOT NULL," +
                        "car_name TEXT NOT NULL," +
                        "user_login TEXT NOT NULL REFERENCES USERS(login)" +
                        ");";

        try (PreparedStatement createStatement = dbManager.getPreparedStatement(create)) {
            createStatement.execute();
        } catch (SQLException e) {
            throw new DataBaseException("cannot create human database");
        }
    }

    @Override
    public int generateNextId() {
        try (PreparedStatement statement = dbManager.getPreparedStatement("SELECT nextval('id')")) {
            ResultSet r = statement.executeQuery();
            r.next();
            return r.getInt(1);
        } catch (SQLException e) {
            return 1;
        }
    }

    private void setHuman(PreparedStatement statement, HumanBeing human) throws SQLException {
        statement.setString(1, human.getName());
        statement.setDouble(2, human.getCoordinates().getX());
        statement.setDouble(3, human.getCoordinates().getY());
        statement.setString(4, DateConverter.dateToString(human.getCreationDate()));
        statement.setBoolean(5, human.checkRealHero());
        statement.setBoolean(6, human.checkHasToothpick());
        statement.setLong(7, human.getImpactSpeed());
        statement.setString(8, human.getSoundtrackName());
        statement.setFloat(9, human.getMinutesOfWaiting());
        statement.setString(10, human.getWeaponType().toString());
        statement.setString(11, human.getCar().getName());
        statement.setString(12, human.getUserLogin());

    }

    private HumanBeing getHuman(ResultSet resultSet) throws SQLException, InvalidDataException {
        Coordinates coordinates = new Coordinates(resultSet.getFloat("coordinates_x"), resultSet.getLong("coordinates_y"));
        Integer id = resultSet.getInt("id");
        String name = resultSet.getString("name");

        Date creationDate = DateConverter.parseDate(resultSet.getString("creation_date"));
        boolean realHero = resultSet.getBoolean("real_hero");

        boolean hasToothpick = resultSet.getBoolean("has_toothpick");

        Integer impactSpeed = resultSet.getInt("impact_speed");
        String soundtrackName = resultSet.getString("soundtrack_name");
        float minutesOfWaiting = resultSet.getFloat("minutes_of_waiting");
        WeaponType weaponType;
        try {
            weaponType = WeaponType.valueOf(resultSet.getString("weapon_type"));
        } catch (IllegalArgumentException e) {
            throw new InvalidEnumException();
        }
        String carName = resultSet.getString("car_name");
        Car car = new Car(carName);
        HumanBeing human = new HumanBeing(name, coordinates, realHero, hasToothpick, impactSpeed, soundtrackName, minutesOfWaiting, weaponType, car);
        human.setCreationDate(creationDate);
        human.setId(id);
        human.setUserLogin(resultSet.getString("user_login"));
        if (!userManager.isPresent(human.getUserLogin())) throw new DataBaseException("no user found");
        return human;
    }

    @Override
    public void add(HumanBeing human) {
        dbManager.setCommitMode();
        dbManager.setSavepoint();
        try (PreparedStatement statement = dbManager.getPreparedStatement(INSERT_HUMANS_QUERY, true)) {
            setHuman(statement, human);
            if (statement.executeUpdate() == 0) throw new DatabaseException();
            ResultSet resultSet = statement.getGeneratedKeys();

            if (!resultSet.next()) throw new DatabaseException();
            human.setId(resultSet.getInt(resultSet.findColumn("id")));

            dbManager.commit();
        } catch (SQLException | DatabaseException e) {
            dbManager.rollback();
            throw new CannotAddException();
        } finally {
            dbManager.setNormalMode();
        }
        super.addWithoutIdGeneration(human);
    }


    @Override
    public void removeByID(Integer id) {
        //language=SQL
        String query = "DELETE FROM HUMANS WHERE id = ?;";
        try {
            PreparedStatement statement = dbManager.getPreparedStatement(query);
            statement.setInt(1, id);
            statement.execute();
        } catch (SQLException e) {
            throw new CannotRemoveException(id);
        }
        super.removeByID(id);
    }

    @Override
    public void removeFirst() {
        removeByID(getCollection().getFirst().getId());
    }

    @Override
    public void updateByID(Integer id, HumanBeing human) {
        dbManager.setCommitMode();
        dbManager.setSavepoint();
        //language = SQL;
        String sql = "UPDATE HUMANS SET " +
                "name=?," +
                "coordinates_x=?," +
                "coordinates_y=?," +
                "creation_date=?," +
                "real_hero=?," +
                "has_toothpick=?," +
                "impact_speed=?," +
                "soundtrack_name=?," +
                "minutes_of_waiting=?," +
                "weapon_type=?," +
                "car_name=?," +
                "user_login=?" +
                "WHERE id=?";
        try (PreparedStatement statement = dbManager.getPreparedStatement(sql)) {
            setHuman(statement, human);
            statement.setInt(13, id);
            statement.execute();
            dbManager.commit();
        } catch (SQLException e) {
            dbManager.rollback();
            Log.logger.error(e);
            throw new CannotUpdateException(id);
        } finally {
            dbManager.setNormalMode();
        }
        super.updateByID(id, human);
    }

    @Override
    public void addIfMax(HumanBeing human) {
        //language=SQL
        String getMaxQuery = "SELECT MAX(impact_speed) FROM HUMANS";

        if (getCollection().isEmpty()) {
            add(human);
            return;
        }
        dbManager.setCommitMode();
        dbManager.setSavepoint();
        try (Statement getStatement = dbManager.getStatement();
             PreparedStatement insertStatement = dbManager.getPreparedStatement(INSERT_HUMANS_QUERY)) {

            ResultSet resultSet = getStatement.executeQuery(getMaxQuery);
            if (!resultSet.next()) throw new CannotAddException();

            long impactspeed = resultSet.getLong(1);
            if (human.getImpactSpeed() < impactspeed)
                throw new DataBaseException("[AddIfMaxException] unable to add, max impact speed is " + impactspeed + " current impact speed is " + human.getImpactSpeed());

            setHuman(insertStatement, human);

            human.setId(resultSet.getInt("id"));
            dbManager.commit();
        } catch (SQLException e) {
            dbManager.rollback();
            throw new CannotAddException();
        } finally {
            dbManager.setNormalMode();
        }
        super.addWithoutIdGeneration(human);
    }

    @Override
    public void addIfMin(HumanBeing human) {
        //language=SQL
        String getMinQuery = "SELECT MIN(impact_speed) FROM HUMANS";

        if (getCollection().isEmpty()) {
            add(human);
            return;
        }
        dbManager.setCommitMode();
        dbManager.setSavepoint();
        try (Statement getStatement = dbManager.getStatement();
             PreparedStatement insertStatement = dbManager.getPreparedStatement(INSERT_HUMANS_QUERY)) {

            ResultSet resultSet = getStatement.executeQuery(getMinQuery);
            if (!resultSet.next()) throw new CannotAddException();

            long impactSpeed = resultSet.getLong(1);
            if (human.getImpactSpeed() > impactSpeed)
                throw new DataBaseException("[AddIfMinException] unable to add, min impact speed is " + impactSpeed + " current impact speed is " + human.getImpactSpeed());

            setHuman(insertStatement, human);

            human.setId(resultSet.getInt("id"));
            dbManager.commit();
        } catch (SQLException e) {
            dbManager.rollback();
            throw new DataBaseException("cannot add due to internal error");
        } finally {
            dbManager.setNormalMode();
        }
        super.addWithoutIdGeneration(human);
    }

    public Collection<HumanBeing> clear(User user) {
        dbManager.setCommitMode();
        dbManager.setSavepoint();
        Set<HumanBeing> removed = new HashSet<>();

        try (PreparedStatement statement = dbManager.getPreparedStatement("DELETE FROM HUMANS WHERE user_login=? RETURNING id")) {
            statement.setString(1, user.getLogin());
            ResultSet resultSet = statement.executeQuery();
            while (resultSet.next()) {
                Integer id = resultSet.getInt(1);
                removed.add(getByID(id));
                removeByID(id);
            }
        } catch (SQLException | CollectionException e) {
            dbManager.rollback();
            deserializeCollection("");
            throw new DatabaseException("cannot clear database");
        } finally {
            dbManager.setNormalMode();
        }
        return  removed;
    }

    @Override
    public void deserializeCollection(String ignored) {
        if (!getCollection().isEmpty()) super.clear();
        //language=SQL
        String query = "SELECT * FROM HUMANS";
        try (PreparedStatement selectAllStatement = dbManager.getPreparedStatement(query)) {
            ResultSet resultSet = selectAllStatement.executeQuery();
            int damagedElements = 0;
            while (resultSet.next()) {
                try {
                    HumanBeing humanBeing = getHuman(resultSet);
                    if (!humanBeing.validate()) throw new InvalidDataException("element is damaged");
                    super.addWithoutIdGeneration(humanBeing);
                } catch (InvalidDataException | SQLException e) {
                    damagedElements += 1;
                }
            }
            if (super.getCollection().isEmpty()) throw new DatabaseException("nothing to load");
            if (damagedElements == 0) Log.logger.info("collection successfully loaded");
            else Log.logger.warn(damagedElements + " elements are damaged");
        } catch (SQLException e) {
            throw new DatabaseException("cannot load");
        }

    }
}