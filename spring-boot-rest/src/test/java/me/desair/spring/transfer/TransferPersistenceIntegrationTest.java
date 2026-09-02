package me.desair.spring.transfer;

import me.desair.spring.transfer.infrastructure.persistence.TransferChunkEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkRepository;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import me.desair.spring.transfer.domain.TransferStatus;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
public class TransferPersistenceIntegrationTest {

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferChunkRepository chunkRepository;

    @Test
    public void testUniqueConstraintPreventsDuplicateChunks() {
        TransferEntity transfer = new TransferEntity();
        transfer.setTransferId("tf_123");
        transfer.setShareToken("st_123");
        transfer.setFileName("test.txt");
        transfer.setFileSize(100);
        transfer.setChunkSize(10);
        transfer.setTotalChunks(10);
        transfer.setStatus(TransferStatus.CREATED);
        transfer.setCreatedAt(Instant.now());
        transfer.setExpiresAt(Instant.now().plusSeconds(3600));
        
        transferRepository.saveAndFlush(transfer);

        TransferChunkEntity chunk1 = new TransferChunkEntity();
        chunk1.setTransferId("tf_123");
        chunk1.setChunkIndex(0);
        chunk1.setSize(10);
        chunk1.setChecksum("abc");
        chunk1.setUploadedAt(Instant.now());
        chunkRepository.saveAndFlush(chunk1);

        TransferChunkEntity chunk2 = new TransferChunkEntity();
        chunk2.setTransferId("tf_123");
        chunk2.setChunkIndex(0); // Duplicate chunk index
        chunk2.setSize(10);
        chunk2.setChecksum("def");
        chunk2.setUploadedAt(Instant.now());
        
        assertThrows(DataIntegrityViolationException.class, () -> {
            chunkRepository.saveAndFlush(chunk2);
        });
    }

    @Test
    public void testForeignKeyCascadesDelete() {
        TransferEntity transfer = new TransferEntity();
        transfer.setTransferId("tf_456");
        transfer.setShareToken("st_456");
        transfer.setFileName("test2.txt");
        transfer.setFileSize(100);
        transfer.setChunkSize(10);
        transfer.setTotalChunks(10);
        transfer.setStatus(TransferStatus.CREATED);
        transfer.setCreatedAt(Instant.now());
        transfer.setExpiresAt(Instant.now().plusSeconds(3600));
        
        transferRepository.saveAndFlush(transfer);

        TransferChunkEntity chunk1 = new TransferChunkEntity();
        chunk1.setTransferId("tf_456");
        chunk1.setChunkIndex(0);
        chunk1.setSize(10);
        chunk1.setChecksum("abc");
        chunk1.setUploadedAt(Instant.now());
        chunkRepository.saveAndFlush(chunk1);

        assertEquals(1, chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_456").size());

        transferRepository.deleteById("tf_456");
        transferRepository.flush();

        assertEquals(0, chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_456").size());
    }

    @Test
    public void testTransferCodePersistenceAndUniqueConstraint() {
        TransferEntity t1 = new TransferEntity();
        t1.setTransferId("tf_code_1");
        t1.setShareToken("st_code_1");
        t1.setTransferCode("ABC7K9");
        t1.setFileName("file1.txt");
        t1.setFileSize(100);
        t1.setChunkSize(10);
        t1.setTotalChunks(10);
        t1.setStatus(TransferStatus.CREATED);
        t1.setCreatedAt(Instant.now());
        t1.setExpiresAt(Instant.now().plusSeconds(3600));
        transferRepository.saveAndFlush(t1);

        assertTrue(transferRepository.findByTransferCode("ABC7K9").isPresent());
        assertEquals("tf_code_1", transferRepository.findByTransferCode("ABC7K9").get().getTransferId());

        // Duplicate transferCode must violate uniqueness constraint
        TransferEntity t2 = new TransferEntity();
        t2.setTransferId("tf_code_2");
        t2.setShareToken("st_code_2");
        t2.setTransferCode("ABC7K9"); // Duplicate code
        t2.setFileName("file2.txt");
        t2.setFileSize(100);
        t2.setChunkSize(10);
        t2.setTotalChunks(10);
        t2.setStatus(TransferStatus.CREATED);
        t2.setCreatedAt(Instant.now());
        t2.setExpiresAt(Instant.now().plusSeconds(3600));

        assertThrows(DataIntegrityViolationException.class, () -> {
            transferRepository.saveAndFlush(t2);
        });
    }
}
