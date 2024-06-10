package org.example.entity;

import lombok.*;

import javax.persistence.*;
import javax.persistence.GenerationType;
import java.sql.Time;
import java.util.Date;

@Getter
@Setter
@EqualsAndHashCode
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "temporary_task_data")
public class TempTaskData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String name;
    private Date date;
    private boolean toRemind;
    private Time remind;
}
