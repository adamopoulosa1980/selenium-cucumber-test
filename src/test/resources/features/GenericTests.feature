Feature: Generic Application Testing

  Scenario Outline: Execute various test scenarios
    Given user is on the "<initialPage>" page
    When user executes test "<testId>"
    Then results match expected outcomes for test "<testId>"

    Examples:
      | initialPage | testId            |
      | login       | login_multi_users |