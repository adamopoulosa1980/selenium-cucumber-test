Feature: Generic Application Testing

  Scenario Outline: Execute various test scenarios
    Given user is on the "<initialPage>" page
    When user executes test "<testId>"
    Then results match expected outcomes for test "<testId>"

    Examples:
      | initialPage | testId            |
      | login       | user_journey      |
      | login       | login_multi_users |
      | upload      | json_upload       |
      | upload      | pdf_upload        |