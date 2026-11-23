package com.example.swiftsim.files;

import com.example.swiftsim.gateway.GatewayService;
import com.example.swiftsim.gateway.SubmitOptions;
import com.example.swiftsim.gateway.SubmitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * File drop interface for the simulator's own {@code data/inbox} (channel FILE). This is not the payment
 * application's kenex tree: put {@code *.fin} / {@code *.txt} / {@code *.xml} here. Each file is one message.
 * Optional routing hint in the file name for MX without AppHdr: {@code name__FROMID__TOID.xml}.
 * Processed files move to {@code data/inbox/processed} (ACK) or {@code data/inbox/error} (NAK) with a {@code .result.txt} beside them.
 * Replies follow the transport mode. They are not written to kenex incoming unless mode is FILE (every copy to the
 * payment application) or the message explicitly asks for FILE. The kenex watcher uses channel FOLDER.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DropFolderWatcher {
    private final GatewayService gateway;

    @Value("${sim.data-dir:./data}")
    private String dataDir;

    @Scheduled(fixedDelayString = "${sim.inbox-poll-ms:2000}", initialDelay = 3000)
    public void poll() {
        Path inbox = Path.of(dataDir, "inbox");
        try {
            Files.createDirectories(inbox.resolve("processed"));
            Files.createDirectories(inbox.resolve("error"));
            List<Path> files;
            try (Stream<Path> s = Files.list(inbox)) {
                files = s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().matches("(?i).*\\.(fin|txt|xml|mt)$")).sorted().toList();
            }
            for (Path f : files) {
                String raw = Files.readString(f);
                String base = f.getFileName().toString();
                String[] parts = base.replaceAll("\\.[^.]+$", "").split("__");
                SubmitOptions.SubmitOptionsBuilder b = SubmitOptions.builder().channel("FILE");
                if (parts.length >= 3) b.from(parts[1]).to(parts[2]);
                SubmitResult r = gateway.submit(raw, b.build());
                Path target = inbox.resolve("ACK".equals(r.getStatus()) ? "processed" : "error").resolve(base);
                Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(target.resolveSibling(base + ".result.txt"),
                        r.getStatus() + " id=" + r.getId() + " type=" + r.getMsgType() + " ref=" + r.getTxRef() + "\n"
                                + String.join("\n", r.getErrors()) + "\n" + String.join("\n", r.getWarnings()) + "\n");
            }
        } catch (Exception e) {
            log.warn("inbox poll failed: {}", e.getMessage());
        }
    }
}
