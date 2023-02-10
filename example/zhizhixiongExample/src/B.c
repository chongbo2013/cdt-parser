#include "A.h"

void foo() {
    int number1 = 100;
    number1 = callMe(number1); // ISSUE NOT CAPTURED AS EXPECTED

}