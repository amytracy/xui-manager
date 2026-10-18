# Fake 3x-ui install.sh for the emulator stand.
echo "Installing 3x-ui $1 (emulator stand)"
sleep 3
mkdir -p "$XUI_MANAGER_XUI_DIR/bin"
cp "@STAND@/fake-x-ui" "$XUI_MANAGER_XUI_DIR/x-ui"
chmod +x "$XUI_MANAGER_XUI_DIR/x-ui"
printf '#!/bin/bash\necho "PrivateKey: WFp1u_7Hgn8YWkRCMZ0X6hAEUJh-yDu3o5eAG4MOLWo"\necho "Password: Xo3s7ZGOX8HWB0bHtz1bplE0g0ECpzRXJ5o5Qp5b0Fs"\n' > "$XUI_MANAGER_XUI_DIR/bin/xray-linux-amd64"
chmod +x "$XUI_MANAGER_XUI_DIR/bin/xray-linux-amd64"
printf 'Username:    %s\n' "$XUI_USERNAME"
printf 'Password:    %s\n' "$XUI_PASSWORD"
echo "x-ui $1 installation finished, it is running now..."
