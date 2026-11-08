#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define BTPROTO_HCI 1
#define HCIDEVUP _IOW('H', 201, int)
#define HCIDEVDOWN _IOW('H', 202, int)
#define HCISETRAW _IOW('H', 220, int)
#define HCI_FILTER 2
#define ANDROID_BLUETOOTH_UID 1002
#define BRIDGE_NAME "caixa.rtl.h4"

struct hci_address { sa_family_t family; uint16_t dev, channel; };
struct hci_filter { uint32_t type, events[2]; uint16_t opcode; };

static int exact_io(int fd, void *buf, size_t size, int output)
{
    unsigned char *pos = buf;
    while (size) {
        ssize_t n = output ? write(fd, pos, size) : read(fd, pos, size);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            return -1;
        pos += n;
        size -= n;
    }
    return 0;
}

static int hci_open(void)
{
    int fd = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI);
    struct hci_address addr = {AF_BLUETOOTH, 0, 0};
    const struct hci_filter filter = {0xffffffff, {0xffffffff, 0xffffffff}, 0};
    if (fd < 0)
        return -1;
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0 ||
        ioctl(fd, HCISETRAW, 1) < 0 ||
        (ioctl(fd, HCIDEVUP, 0) < 0 && errno != EALREADY) ||
        setsockopt(fd, 0, HCI_FILTER, &filter, sizeof(filter)) < 0) {
        int error = errno;
        close(fd);
        errno = error;
        return -1;
    }
    return fd;
}

static void relay(int client, int hci)
{
    unsigned char packet[65540];
    struct pollfd fds[] = {{client, POLLIN, 0}, {hci, POLLIN, 0}};
    while (poll(fds, 2, -1) > 0) {
        if (fds[0].revents & POLLIN) {
            if (exact_io(client, packet, 1, 0))
                break;
            size_t header = packet[0] == 2 ? 5 : 4;
            if (packet[0] < 1 || packet[0] > 3 ||
                exact_io(client, packet + 1, header - 1, 0))
                break;
            size_t payload = packet[header - 1];
            if (packet[0] == 2)
                payload = packet[3] | (packet[4] << 8);
            if (exact_io(client, packet + header, payload, 0) ||
                write(hci, packet, header + payload) != (ssize_t)(header + payload)) {
                perror("HCI send");
                break;
            }
        }
        if (fds[1].revents & POLLIN) {
            ssize_t n = read(hci, packet, sizeof(packet));
            if (n <= 0 || exact_io(client, packet, n, 1))
                break;
        }
        if ((fds[0].revents | fds[1].revents) & (POLLERR | POLLHUP | POLLNVAL))
            break;
    }
}

int main(void)
{
    signal(SIGPIPE, SIG_IGN);
    struct sockaddr_un address = {.sun_family = AF_UNIX, .sun_path = "\0" BRIDGE_NAME};
    int server = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server < 0 ||
        bind(server, (struct sockaddr *)&address,
             offsetof(struct sockaddr_un, sun_path) + sizeof(BRIDGE_NAME)) < 0 ||
        listen(server, 1) < 0) {
        perror("bridge listen");
        return 1;
    }
    for (;;) {
        int client = accept(server, NULL, NULL);
        if (client < 0) {
            if (errno == EINTR)
                continue;
            perror("bridge accept");
            return 1;
        }
        struct ucred peer;
        socklen_t length = sizeof(peer);
        if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &peer, &length) == 0 &&
            peer.uid == ANDROID_BLUETOOTH_UID) {
            int hci = -1;
            for (unsigned attempt = 0; attempt < 12; ++attempt) {
                hci = hci_open();
                if (hci >= 0 || (errno != ENODEV && errno != EAGAIN))
                    break;
                sleep(1);
            }
            if (hci < 0)
                perror("HCI open");
            else {
                puts("Android connected to USB HCI");
                fflush(stdout);
                relay(client, hci);
                ioctl(hci, HCIDEVDOWN, 0);
                ioctl(hci, HCISETRAW, 0);
                close(hci);
            }
        }
        close(client);
    }
}
