/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.schneider.mstt.cost.center.mirror.service;

import com.schneider.mstt.cost.center.mirror.exceptions.SciformaException;
import com.sciforma.psnext.api.DataViewRow;
import com.sciforma.psnext.api.Global;
import com.sciforma.psnext.api.LockException;
import com.sciforma.psnext.api.PSException;
import com.sciforma.psnext.api.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.util.StopWatch;

@Configuration
@PropertySource("file:${user.dir}/conf/psconnect.properties")
public class SciformaService {

    private static final Logger LOG = Logger.getLogger(SciformaService.class);
    private static final String DATAVIEW_NAME = "Cost center mirror";

    @Value("${mstt.psnext.url}")
    private String url;
    @Value("${mstt.psnext.login}")
    private String username;
    @Value("${mstt.psnext.password}")
    private String password;

    private Session session;
    private Global global;

    public boolean createConnection() {

        try {

            LOG.info("Connection to " + url + " with username " + username);
            session = new Session(url);
            session.login(username, password.toCharArray());
            LOG.info("Connection successful");

            return true;

        } catch (PSException e) {
            LOG.error("Failed to connect to sciforma : " + e.getMessage(), e);
        }

        return false;

    }

    public void closeConnection() {

        try {

            if (session.isLoggedIn()) {
                session.logout();
                LOG.info("Logout successful");
            }

        } catch (PSException e) {
            LOG.error("Failed to logout", e);
        }

    }

    public List<DataViewRow> getDataView() {

        if (session.isLoggedIn()) {

            if (global == null) {
                global = new Global();
            }

            try {

                LOG.info("Retrieving data view : " + DATAVIEW_NAME);
                return session.getDataViewRowList(DATAVIEW_NAME, global);

            } catch (PSException e) {
                LOG.error("Failed to retrieve data view : " + DATAVIEW_NAME, e);
            }

        }

        return new ArrayList<>();

    }

    public DataViewRow createDataViewRow() throws PSException {
        return new DataViewRow(DATAVIEW_NAME, global);
    }

    public boolean openGlobal() {

        if (global == null) {
            global = new Global();
        }

        try {
            global.lock();
            LOG.info("Global locked");
            return true;

        } catch (LockException ex) {
            LOG.error("Global already locked by : " + ex.getLockingUser());
        } catch (PSException ex) {
            LOG.error("Failed to lock global");
        }

        return false;

    }

    public boolean saveAndCloseGlobal() {

        boolean result = false;

        try {
            global.save(false);
            LOG.info("Global saved");
            result = true;

        } catch (PSException ex) {
            LOG.error("Failed to save global");
        } finally {

            try {
                global.unlock();
                LOG.info("Global unlocked");
                result = false;
            } catch (PSException ex) {
                LOG.error("Failed to unlock global");
            }

        }

        return result;

    }

}
