package com.moodride.routeworker.watchdog;

import com.moodride.datamodels.RouteJob;
import com.moodride.routeworker.producer.RouteCompletionProducer;
import com.moodride.routeworker.producer.RouteJobDlqProducer;
import com.moodride.routeworker.repository.RouteJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeoutWatchdogTest {

    @Mock
    private RouteJobRepository jobRepository;

    @Mock
    private RouteCompletionProducer completionProducer;

    @Mock
    private RouteJobDlqProducer dlqProducer;

    @Test
    void timeoutRequeuesWhenRetryAvailable() {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setStartedAt(Instant.now().minusSeconds(120));
        job.setRetryCount(0);
        job.setMaxRetries(2);

        when(jobRepository.findByStatusAndStartedAtBefore(any(), any())).thenReturn(List.of(job));

        TimeoutWatchdog watchdog = new TimeoutWatchdog(jobRepository, completionProducer, dlqProducer, 30);
        watchdog.checkForTimedOutJobs();

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository).save(saved.capture());
        RouteJob updated = saved.getValue();

        assertThat(updated.getStatus()).isEqualTo(RouteJob.JobStatus.QUEUED);
        assertThat(updated.getRetryCount()).isEqualTo(1);
        verify(completionProducer, never()).publishFailure(any(), any(), anyString());
        verify(dlqProducer, never()).publishToDlq(any(), anyString(), anyString());
    }

    @Test
    void timeoutPublishesFailureAndDlqWhenRetriesExhausted() {
        RouteJob job = new RouteJob(UUID.randomUUID(), 45.51, -122.67, 90, "coastal");
        job.setId(UUID.randomUUID());
        job.setStatus(RouteJob.JobStatus.PROCESSING);
        job.setStartedAt(Instant.now().minusSeconds(120));
        job.setRetryCount(2);
        job.setMaxRetries(2);

        when(jobRepository.findByStatusAndStartedAtBefore(any(), any())).thenReturn(List.of(job));

        TimeoutWatchdog watchdog = new TimeoutWatchdog(jobRepository, completionProducer, dlqProducer, 30);
        watchdog.checkForTimedOutJobs();

        ArgumentCaptor<RouteJob> saved = ArgumentCaptor.forClass(RouteJob.class);
        verify(jobRepository).save(saved.capture());
        RouteJob updated = saved.getValue();

        assertThat(updated.getStatus()).isEqualTo(RouteJob.JobStatus.FAILED);
        verify(completionProducer).publishFailure(job.getId(), job.getUserId(), "Route generation timed out after retries");
        verify(dlqProducer).publishToDlq(job.getId(), "Route generation timed out after retries", job.getId().toString());
    }
}

