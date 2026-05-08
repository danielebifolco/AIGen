// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.scheduler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unisannio.bomgenerator.PipeManager.ToolConfig.AgentDescriptor;
import it.unisannio.bomgenerator.PipeManager.ToolConfig.GoalDescriptor;
import it.unisannio.bomgenerator.PipeManager.ToolConfig.TeamDescriptor;
import it.unisannio.bomgenerator.builders.MySpdxPackageBuilder;

public class AgentsCoordinator {

    String pipeVersion = "3";
    private List<Hashtable<String, Object>> executionResults = new ArrayList<>();

    Logger logger = LoggerFactory.getLogger(AgentsCoordinator.class);

    /**
     * Returns a list of all execution results. Each element is a pair with
     * the key being the method which generated the result. The result is reported
     * as an Object instance for compatibility with the serializer interface.
     * This is automatically initialized by the method
     * {@link #executeInvocationsPipes(List) executeInvocationsPipes}
     * 
     * @return a list of hashtables, each hashtable contains the results of the
     *         execution of the methods in the pipe, with the key being the method
     *         * name and the value being the result of the method execution.
     *         * @see executeInvocationsPipes(List<InvocationsPipe> pipes)
     *         * @see FileSerializer.serialize(List<Hashtable<String, Object>> data)
     * 
     */
    public List<Hashtable<String, Object>> getExecutionResults() {
        return executionResults;
    }

    public void validateTeams(List<TeamDescriptor> teams) {
        for (TeamDescriptor team : teams) {
            if (!team.type.equals("Dataset") && !team.type.equals("AI")) {
                logger.error("Team " + team.teamName + " has an invalid type: " + team.type
                        + ". Valid types are 'AI' or 'Dataset'.");
                throw new IllegalArgumentException("Invalid team type: " + team.type);
            }
            if (team.type.equals("Dataset")) {
                if (team.tags.contains("train") && team.tags.contains("test")) {
                    logger.error("Team " + team.teamName + " has both 'train' and 'test' tags. "
                            + "This may lead to confusion in the dataset processing.");

                    throw new IllegalArgumentException("Team " + team.teamName + " has both 'train' and 'test' tags.");
                }

                if (!team.tags.contains("train") && !team.tags.contains("test")) {
                    logger.error("Team " + team.teamName + " has neither 'train' nor 'test' tags. "
                            + "This may lead to confusion in the dataset processing.");
                    throw new IllegalArgumentException(
                            "Team " + team.teamName + " has neither 'train' nor 'validation' tags.");
                }

            }
        }
    }

    /**
     * transform teams of the PipeManager into InvocationsPipes which can be
     * executed by this coordinator with the method
     * {@link #executeInvocationsPipes(List)}
     * * @param teams list of teams to transform into InvocationsPipes
     * * @return a list of InvocationsPipes, each pipe contains a list of
     * invocations
     */
    @SuppressWarnings("unchecked")
    public List<TeamInvocations> initInvocationsPipes(List<TeamDescriptor> teams) {

        List<TeamInvocations> pipes = new ArrayList<>();

        // Instantiate Reflections once for the target package.
        String basePackage = "it.unisannio.bomgenerator.builders.buildersV" + pipeVersion;
        Reflections reflections = new Reflections(basePackage);
        Set<Class<? extends MySpdxPackageBuilder>> allBuilderClasses = reflections
                .getSubTypesOf(MySpdxPackageBuilder.class);

        for (TeamDescriptor team : teams) {
            List<Invocation> invocations = new LinkedList<>();

            for (AgentDescriptor agent : team.agents) {
                try {
                    // Find a class whose simple name matches agentName.
                    Optional<Class<? extends MySpdxPackageBuilder>> builderClassOpt = allBuilderClasses.stream()
                            .filter(c -> c.getSimpleName().equals(agent.agentName))
                            .findFirst();

                    if (builderClassOpt.isEmpty()) {
                        logger.warn("Builder class not found for agent: " + agent.agentName
                                + ". No builder available with this name, pipe will continue with remaining builders.");
                        continue;
                    }

                    Class<? extends MySpdxPackageBuilder> builderClass = builderClassOpt.get();

                    MySpdxPackageBuilder agentInstance = instantiateBuilder(agent.agentName, agent.codeName,
                            builderClass);

                    List<Invocation> newInvocations = createInvocationsList(agentInstance, agent.goals, team);

                    if (newInvocations == null) {
                        logger.warn("Agent " + agent.agentName + " has not been initialized correctly, "
                                + "no invocations will be added to the pipe for this agent");
                        continue;
                    }

                    invocations.addAll(newInvocations);
                } catch (Exception e) {
                    logger.warn("Unexpected error initializing builder for agent: " + agent.agentName, e);
                }
            }

            pipes.add(new TeamInvocations(invocations));
        }

        return pipes;
    }

    private List<Invocation> createInvocationsList(MySpdxPackageBuilder agentInstance, List<GoalDescriptor> goals,
            TeamDescriptor team) {

        List<Invocation> invocations = new ArrayList<>();

        for (GoalDescriptor goal : goals) {

            try {

                String methodName = "add" + goal.fieldName;

                // * means all fields provided by the agent
                if (methodName.equals("add*") || methodName.equals("add")) {
                    Method[] methods = agentInstance.getClass().getDeclaredMethods();

                    for (Method method : methods) {

                        if (!method.getName().contains("add"))
                            continue;
                        invocations.add(new Invocation(method, agentInstance, goal.priority));
                    }

                } else {

                    Method method = agentInstance.getClass().getDeclaredMethod(methodName);

                    invocations.add(new Invocation(method, agentInstance, goal.priority));
                }

            } catch (NoSuchMethodException e) {
                logger.error("Goal not found: " + goal.fieldName + " for agent: "
                        + agentInstance.getClass().getName()
                        + " check the documentation to see which goals are available for this agent");
            } catch (SecurityException e) {
                e.printStackTrace();
            }
        }

        boolean initResult = ((MySpdxPackageBuilder) agentInstance).initBuilder();

        if (!initResult)
            return null;

        return invocations;
    }

    private <T> T instantiateBuilder(String agentName, String codeName, Class<T> builderClass) {
        logger.info("Initializing builder: " + agentName);

        try {
            T builder;

            builder = builderClass.getConstructor(String.class, String.class).newInstance(agentName, codeName);

            return builder;

        } catch (Exception e) {
            logger.warn("Error initializing builder: " + agentName
                    + " no builder available with this name, pipe will continue using the remaining builders described in the pipe configuration file");
            logger.error("Builder initialization error details:", e);
        }

        return null;

    }

    /**
     * 
     * 
     * Executes the invocations pipes, each pipe contains a list of invocations
     * before executing it this methods schedules the invocations by priority, it
     * avoids execution of an invocation if a higher-priority invocation has already
     * been executed successfully.
     * 
     * the results are stored in the {@link #executionResults} list as key-value
     * pairs
     * where the key is the method name and the value is the result of the method
     * execution.
     * 
     * @param pipes list of invocations pipes to execute, each pipe contains a list
     *              of
     *              invocations to execute.
     */
    public void executeInvocationsPipes(List<TeamInvocations> pipes) {

        for (TeamInvocations pipe : pipes) {
            Hashtable<String, Object> executedMethods = new Hashtable<>();
            List<Invocation> invocations = pipe.invocations;

            // Sort invocations (only if with the same method name)
            invocations.sort((i1, i2) -> {
                if (i1.method.getName().equals(i2.method.getName())) {
                    return Integer.compare(i1.priority, i2.priority);
                } else
                    return 0;
            });

            for (Invocation invocation : invocations) {
                if (executedMethods.containsKey(invocation.method.getName())) {
                    logger.info(invocation.method.getName()
                            + " by agent: " + invocation.getMethod().getDeclaringClass().getName()
                            + " was not executed: a higher-priority agent has already retrieved this information");

                    continue; // Skip if this method has already been executed successfully
                }
                try {

                    // EXECUTE PIPE
                    Method method = invocation.getMethod();
                    MySpdxPackageBuilder builder = (MySpdxPackageBuilder) invocation.getBuilder();
                    Object result;

                    result = method.invoke(builder);

                    // If a higher-priority goal has succeeded, do not execute it again.
                    if (result != null)
                        executedMethods.put(method.getName(), result);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            this.executionResults.add(executedMethods);
        }

    }

    public class TeamInvocations {

        List<Invocation> invocations;

        public TeamInvocations(List<Invocation> invocations) {
            this.invocations = invocations;
        }

    }

    public class Invocation {
        enum InvocationType {
            AI, DATASET
        }

        private final java.lang.reflect.Method method;
        private final MySpdxPackageBuilder builder;
        private int priority;

        public Invocation(java.lang.reflect.Method method, MySpdxPackageBuilder builder, int priority) {
            this.method = method;
            this.builder = builder;
            this.priority = priority;
        }

        public java.lang.reflect.Method getMethod() {
            return method;
        }

        public Object getBuilder() {
            return builder;
        }
    }

}
