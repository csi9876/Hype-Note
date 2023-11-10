package com.surf.auth.member.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentShareRequestDto {

    private int userPk;
    private String editorId;
    private String sharedNickName;
}
