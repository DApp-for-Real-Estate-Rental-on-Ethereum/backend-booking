package ma.fstt.bookingservice.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReclamationRequest {
    private Long bookingId;
    private Long userId;
    private String reclamationType;
    private String description;
}

