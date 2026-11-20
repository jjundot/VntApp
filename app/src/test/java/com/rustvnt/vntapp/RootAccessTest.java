package com.rustvnt.vntapp;

import org.junit.Test;

public class RootAccessTest {
    @Test public void isPresentDoesNotThrowOnDesktopJvm() {
        RootAccess.isPresent();
    }
}
