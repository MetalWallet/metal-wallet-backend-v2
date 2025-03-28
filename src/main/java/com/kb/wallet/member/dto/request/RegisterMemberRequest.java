package com.kb.wallet.member.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;


@Builder
@Getter
public class RegisterMemberRequest {

  @NotBlank(message = "이메일은 필수 입력 항목입니다.")
  @Email(message = "유효한 이메일 주소를 입력해 주세요.")
  private String email;

  @NotBlank(message = "비밀번호는 필수 입력 항목입니다.")
  private String password;

  @NotBlank(message = "이름은 필수 입력 항목입니다.")
  @Size(max = 10, message = "이름의 길이는 10자 이내여야 합니다.")
  private String name;

  @NotBlank(message = "전화번호는 필수 입력 항목입니다.")
  @Size(min = 11, max = 11, message = "전화번호는 숫자(11자리)만 입력해주세요.")
  private String phone;

  @NotBlank(message = "핀번호는 필수 입력 항목입니다.")
  @Size(min = 6, max = 6, message = "핀번호는 6자리여야 합니다.")
  private String pinNumber;

  // Jackson을 위한 JsonCreator와 JsonProperty 추가
  @JsonCreator
  public RegisterMemberRequest(
    @JsonProperty("email") String email,
    @JsonProperty("password") String password,
    @JsonProperty("name") String name,
    @JsonProperty("phone") String phone,
    @JsonProperty("pinNumber") String pinNumber) {

    this.email = email;
    this.password = password;
    this.name = name;
    this.phone = phone;
    this.pinNumber = pinNumber;
  }
}
