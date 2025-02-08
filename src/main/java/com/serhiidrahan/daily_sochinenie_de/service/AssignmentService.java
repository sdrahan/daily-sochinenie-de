package com.serhiidrahan.daily_sochinenie_de.service;

import com.serhiidrahan.daily_sochinenie_de.entity.Assignment;
import com.serhiidrahan.daily_sochinenie_de.entity.AssignmentTopic;
import com.serhiidrahan.daily_sochinenie_de.entity.User;
import com.serhiidrahan.daily_sochinenie_de.enums.AssignmentState;
import com.serhiidrahan.daily_sochinenie_de.repository.AssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Random;

@Service
public class AssignmentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssignmentService.class);

    private final AssignmentRepository assignmentRepository;
    private final AssignmentTopicService assignmentTopicService;
    private final Random random = new Random();

    public AssignmentService(AssignmentRepository assignmentRepository,
                             AssignmentTopicService assignmentTopicService) {
        this.assignmentRepository = assignmentRepository;
        this.assignmentTopicService = assignmentTopicService;
    }

    /**
     * Changes the state of an assignment (e.g., mark as DONE or CANCELLED).
     */
    @Transactional
    public void changeAssignmentState(Assignment assignment, AssignmentState newState) {
        assignment.setState(newState);
        assignmentRepository.save(assignment);
    }

    /**
     * Returns the user's current active assignment.
     * Throws an exception if multiple active assignments exist.
     */
    @Transactional(readOnly = true)
    public Assignment getCurrentActiveAssignment(User user) {
        List<Assignment> activeAssignments = assignmentRepository.findAssignmentsByUserIdAndStates(user.getId(), List.of(AssignmentState.ACTIVE, AssignmentState.SUBMITTED));

        if (activeAssignments.isEmpty()) {
            throw new IllegalStateException("No active assignment found.");
        } else if (activeAssignments.size() > 1) {
            throw new IllegalStateException("Multiple active assignments found for user.");
        }

        return activeAssignments.get(0);
    }

    /**
     * Assigns a new topic to the user and marks it as NEW.
     * Ensures the user gets a topic they haven't had before.
     */
    @Transactional
    public Assignment assignNewTopic(User user) {
        List<Long> assignedTopicIds = assignmentRepository.findAssignedTopicIdsByUserId(user.getId());

        List<AssignmentTopic> availableTopics = assignmentTopicService.getUnassignedActiveTopics(assignedTopicIds);
        if (availableTopics.isEmpty()) {
            throw new IllegalStateException("No available new topics for the user.");
        }

        // Pick a random topic
        AssignmentTopic randomTopic = availableTopics.get(random.nextInt(availableTopics.size()));

        // Create a new assignment
        Assignment newAssignment = new Assignment();
        newAssignment.setUser(user);
        newAssignment.setTopic(randomTopic);
        newAssignment.setState(AssignmentState.ACTIVE);

        return assignmentRepository.save(newAssignment);
    }

    @Transactional
    public void setTelegramMessageId(Assignment assignment, Integer telegramMessageId) {
        assignment.setTelegramMessageId(telegramMessageId);
        assignmentRepository.save(assignment);
    }

    /**
     * Cancels the current active assignment and assigns a new one.
     */
    @Transactional
    public Assignment cancelAndReassign(User user) {
        Assignment activeAssignment = getCurrentActiveAssignment(user);

        // Mark the current assignment as CANCELLED
        activeAssignment.setState(AssignmentState.CANCELLED);
        assignmentRepository.save(activeAssignment);

        // Assign a new topic
        return assignNewTopic(user);
    }
}
