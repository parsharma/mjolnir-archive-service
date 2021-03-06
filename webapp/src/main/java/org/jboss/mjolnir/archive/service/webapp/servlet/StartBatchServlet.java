package org.jboss.mjolnir.archive.service.webapp.servlet;

import org.jboss.logging.Logger;
import org.jboss.mjolnir.archive.service.webapp.Constants;

import javax.batch.operations.JobOperator;
import javax.batch.operations.NoSuchJobException;
import javax.batch.runtime.BatchRuntime;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/**
 * Triggers a batch job that archives repositories of offboarder users and removes their GH team memberships.
 */
@WebServlet("/archive-users")
public class StartBatchServlet extends HttpServlet {

    private Logger logger = Logger.getLogger(getClass());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/plain");

        JobOperator jobOperator = BatchRuntime.getJobOperator();
        try {
            List<Long> runningExecutions = jobOperator.getRunningExecutions(Constants.REMOVE_MEMBERSHIP_JOB_NAME);

            if (runningExecutions.size() > 0) {
                resp.getOutputStream().println("Job already running.");
                logger.infof("Jobs already running: ", runningExecutions.size());
                return;
            }
        } catch (NoSuchJobException e) {
            // pass
        }

        long executionId = jobOperator.start(Constants.REMOVE_MEMBERSHIP_JOB_NAME, new Properties());
        logger.infof("Started job ID %d", executionId);
        resp.getOutputStream().println("Started execution ID: " + executionId);
    }
}
