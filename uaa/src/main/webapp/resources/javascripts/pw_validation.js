// validate_password contains the logic for client side password validation.
// It is passed in rule threshold information and then sets up watchers to check
// the password as it is typed in. It then compares the password to the value
// of the confirmation password.
// This function is to be used in with the html from pw_validation.html
function validate_password(special_count, uppercase_count, lowercase_count,
                            number_count, length_count, password_field,
                            confirm_password_field) {
    // Set the values for the password policy requirements into the html.
    document.getElementById("special-count").innerHTML=''+special_count+'';
    document.getElementById("uppercase-count").innerHTML=''+uppercase_count+'';
    document.getElementById("lowercase-count").innerHTML=''+lowercase_count+'';
    document.getElementById("number-count").innerHTML=''+number_count+'';
    document.getElementById("length-count").innerHTML=''+length_count+'';

    // Hide rules that aren't set or set to zero.
    if ( special_count === 0 ) {$('#special-req').hide();}
    if ( uppercase_count === 0 ) {$('#uppercase-req').hide();}
    if ( lowercase_count === 0 ) {$('#lowercase-req').hide();}
    if ( number_count === 0 ) {$('#number-req').hide();}
    if ( length_count === 0 ) {$('#length-req').hide();}

    // Create a simple boolean to check if no policy is set.
    var no_rules = ((special_count === 0) && (uppercase_count === 0) &&
                    (lowercase_count === 0) && (number_count === 0) &&
                    (length_count === 0));
    // If no policy, make sure it stays hidden.
    if (no_rules == true) {$('#password-requirements').hide();}

    // validate_field is a helper function.
    // Depending on the current conditional, it will set the CSS class for the
    // corresponding 'html_field' text to either 'text-success' or 'text-danger'
    function validate_field(error_case, html_field) {
        if ( error_case === true ) {
            $(html_field).removeClass('text-success').addClass('text-danger');
        } else {
            $(html_field).removeClass('text-danger').addClass('text-success');
        }
    }

    // compare_new_passwords is a helper function.
    // It will look at the values of password_field and confirm_password_field
    // and compare them. If equal, it will set a placeholder text to read 'DO'.
    // Else, it will be set to 'DO NOT'.
    // This placeholder text will either fit in a broader text to either read:
    // "Passwords 'DO/ DO NOT' match."
    function compare_new_passwords() {
        // Get the password value.
        var pw = $("input[type='password'][name='" + password_field + "']").val();
        // Get the confirm password value.
        var confirm_pw = $("input[type='password'][name='" + confirm_password_field + "']").val();
        if (pw === confirm_pw) {
            document.getElementById("match-passwords").innerHTML='DO';
        } else {
            document.getElementById("match-passwords").innerHTML='DO NOT';
        }
    }

    // Setup password validator.
    $("input[type='password'][name='" + password_field + "']").keyup(function() {
        // Compare new passwords.
        compare_new_passwords();

        // If no rules, no need to do anything else.
        if ( no_rules == true ) {return;}

        // Get the password value.
        var pw = $(this).val();

        // Validate the length of the password.
        validate_field( ( pw.length < length_count ), '#length-req');

        // Validate the number of special characters.
        validate_field(( ( pw.length - pw.replace( /[^0-9a-zA-Z]/g, '' ).length ) < special_count ), '#special-req');

        // Validate the number of lowercase letters
        validate_field(( (pw.length - pw.replace(/[a-z]/g, '').length) < lowercase_count ), '#lowercase-req');

        // Validate the number of uppercase letters
        validate_field(( (pw.length - pw.replace(/[A-Z]/g, '').length) < uppercase_count ), '#uppercase-req');

        // Validate the number of digits
        validate_field(( (pw.length - pw.replace(/[0-9]/g, '').length) < number_count ), '#number-req');
    }).focus(function() {  // When the user clicks into the password field.
        // If no rules, no need to do anything.
        if ( no_rules === true ) {return;}
        // Else, show the requirements box.
        $('#password-requirements').show();
    }).blur(function() {  // When the user clicks out of the password field.
        // If no rules, no need to do anything.
        if ( no_rules === true ) {return;}
        // Else, hide the requirements box.
        $('#password-requirements').hide();
    });

    // Setup matcher for password confirmation.
    $("input[type='password'][name='" + confirm_password_field + "']").keyup(function() {
        compare_new_passwords();
    }).focus(function() {  // When the user clicks into the confirm password field.
        // Show the confirmation requirement box.
        $('#pw_confirm-requirement').show();
    }).blur(function() {  // When the user clicks out of the password field.
        // Hide the confirmation requirement box.
        $('#pw_confirm-requirement').hide();
    });
}
