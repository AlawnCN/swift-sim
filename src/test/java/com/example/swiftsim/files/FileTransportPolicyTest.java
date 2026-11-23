package com.example.swiftsim.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileTransportPolicyTest {
    @TempDir
    Path tmp;

    @Test
    void layoutIsPaymentApplicationPerspective() {
        KenexLayout.Result ok = KenexLayout.resolve("/path/to/kenex", "");
        assertNull(ok.error);
        assertEquals(Path.of("/path/to/kenex/outgoing/mt"), ok.layout.outgoingMt);
        assertEquals(Path.of("/path/to/kenex/outgoing/mx"), ok.layout.outgoingMx);
        assertEquals(Path.of("/path/to/kenex/incoming/mt"), ok.layout.incomingMt);
        assertEquals(Path.of("/path/to/kenex/incoming/mx"), ok.layout.incomingMx);
        assertEquals(Path.of("/path/to/kenex/temp/incoming"), ok.layout.appTempIncoming);
        assertEquals(Path.of("/path/to/kenex/temp/outgoing/mt"), ok.layout.archived("MT"));
        assertEquals(Path.of("/path/to/kenex/temp/outgoing/mx"), ok.layout.archived("MX"));
        assertEquals(Path.of("/path/to/kenex/.swiftsim"), ok.layout.stateRoot);
        assertTrue(ok.layout.errorMt.startsWith(ok.layout.stateRoot));
        assertFalse(ok.layout.archivedMt.startsWith(ok.layout.stateRoot));
        assertNotNull(KenexLayout.resolve("/", "").error);
        assertNotNull(KenexLayout.resolve("/tmp", "").error);
        assertNotNull(KenexLayout.resolve("/path/to/kenex", "/path/to/kenex/temp/incoming").error);
        assertNotNull(KenexLayout.resolve("/path/to/kenex", "/path/to/kenex/temp/outgoing").error);
        assertNotNull(KenexLayout.resolve("/path/to/kenex", "/path/to/kenex/outgoing/mt/state").error);
        assertNotNull(KenexLayout.resolve("/path/to/kenex", "/path/to/kenex/incoming/mx").error);
    }

    @Test
    void temporarySymlinkAndExtensionAreNotIngested() throws Exception {
        Path dir = tmp.resolve("outgoing/mt");
        Files.createDirectories(dir);
        Path partial = dir.resolve("MT700PYHNHFCY3PUDF7S.txt.partial");
        Files.writeString(partial, "{1:F01TEST");
        Path hidden = dir.resolve(".MT700HIDDEN.txt");
        Files.writeString(hidden, "{1:F01TEST");
        Path real = dir.resolve("real-body.txt");
        Files.writeString(real, "{1:F01TEST");
        Path link = dir.resolve("MT700LINK.txt");
        Files.createSymbolicLink(link, real);
        Path xml = dir.resolve("MXPYHNHE327RFJ24G.xml");
        Files.writeString(xml, "<x/>");
        Path zero = dir.resolve("MT700ZERO.txt");
        Files.write(zero, new byte[0]);

        assertEquals(InboundPolicy.Skip.TEMP, InboundPolicy.classify(dir, partial, "MT", 1_000_000));
        assertEquals(InboundPolicy.Skip.TEMP, InboundPolicy.classify(dir, hidden, "MT", 1_000_000));
        assertEquals(InboundPolicy.Skip.SYMLINK, InboundPolicy.classify(dir, link, "MT", 1_000_000));
        assertEquals(InboundPolicy.Skip.EXTENSION, InboundPolicy.classify(dir, xml, "MT", 1_000_000));
        assertEquals(InboundPolicy.Skip.EMPTY, InboundPolicy.classify(dir, zero, "MT", 1_000_000));
        assertEquals(InboundPolicy.Skip.OK, InboundPolicy.classify(dir, real, "MT", 1_000_000));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("{1:F01TEST", Files.readString(partial));
    }

    @Test
    void stableFileWaitsForUnchangedSize() {
        StabilityTracker tracker = new StabilityTracker();
        assertFalse(tracker.ready("a", 0, 1, 0, 0));
        assertFalse(tracker.ready("a", 10, 5, 1000, 0));
        assertFalse(tracker.ready("a", 10, 5, 1000, 999));
        assertFalse(tracker.ready("a", 11, 5, 1000, 2000));
        assertTrue(tracker.ready("a", 11, 5, 1000, 3000));
        assertTrue(new StabilityTracker().ready("b", 4, 1, 0, 50));
    }

    @Test
    void outboundNamesMatchConventionAndDoNotReplaceExistingBytes() throws Exception {
        assertEquals("MT700", OutboundNames.prefix("MT", "MT700", false));
        assertEquals("MTACK", OutboundNames.prefix("SYS", "ACK", false));
        assertEquals("MX", OutboundNames.prefix("MX", "tsrv.019.001.01", true));
        String mt = OutboundNames.nextName("MT", "MT700", "{1:F01");
        String mx = OutboundNames.nextName("MX", "tsrv.001.001.01", "<Document>");
        assertTrue(mt.matches("MT700[A-Z0-9]{16}\\.txt"), mt);
        assertTrue(mx.matches("MX[A-Z0-9]{16}\\.xml"), mx);

        Path dir = tmp.resolve("incoming/mt");
        Files.createDirectories(dir);
        byte[] original = "keep-me".getBytes(StandardCharsets.UTF_8);
        Path kept = AtomicFiles.publishNew(dir, "USERKEEP.txt", original);
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> AtomicFiles.publishNew(dir, "USERKEEP.txt", "other".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(original, Files.readAllBytes(kept));
        Path second = AtomicFiles.publishNew(dir, "MT700AAAAAAAAAAAAAAAB.txt", "second".getBytes(StandardCharsets.UTF_8));
        assertEquals("second", Files.readString(second));
        try (var list = Files.list(dir)) {
            assertTrue(list.noneMatch(p -> p.getFileName().toString().startsWith(".")));
        }
    }

    @Test
    void encodingRejectsBrokenUtf8AndStripsBom() throws Exception {
        assertEquals("ABC", FileTransportService.decode("ABC".getBytes(StandardCharsets.UTF_8)));
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Z'};
        assertEquals("Z", FileTransportService.decode(bom));
        assertThrows(CharacterCodingException.class, () -> FileTransportService.decode(new byte[] {(byte) 0x80}));
    }
}
