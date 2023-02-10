#include "A.h"

int main() {
    int number1 = 100;
    number1 = callMe(number1); // ISSUE NOT CAPTURED AS EXPECTED

    int number2 = 100;
    number2 = number2; // VIOLATION RAISED - SELF ASSIGNMENT
}