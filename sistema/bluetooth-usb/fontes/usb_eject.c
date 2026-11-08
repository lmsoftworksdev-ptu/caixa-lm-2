#include <errno.h>
#include <fcntl.h>
#include <scsi/sg.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int send_cdb(int fd, const char *label, unsigned char *cdb) {
    unsigned char sense[64] = {0};
    sg_io_hdr_t io;
    memset(&io, 0, sizeof(io));
    io.interface_id = 'S';
    io.dxfer_direction = SG_DXFER_NONE;
    io.cmd_len = 6;
    io.mx_sb_len = sizeof(sense);
    io.cmdp = cdb;
    io.sbp = sense;
    io.timeout = 5000;
    if (ioctl(fd, SG_IO, &io) < 0) {
        fprintf(stderr, "%s ioctl: %s\n", label, strerror(errno));
        return -1;
    }
    printf("%s status=0x%02x host=0x%04x driver=0x%04x sense_len=%u\n",
           label, io.status, io.host_status, io.driver_status, io.sb_len_wr);
    return io.status == 0 && io.host_status == 0 ? 0 : -1;
}

int main(int argc, char **argv) {
    const char *device = argc > 1 ? argv[1] : "/dev/sg0";
    unsigned char allow_removal[6] = {0x1e, 0, 0, 0, 0, 0};
    unsigned char eject[6] = {0x1b, 0, 0, 0, 0x02, 0};
    int fd = open(device, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", device, strerror(errno));
        return 2;
    }
    int first = send_cdb(fd, "ALLOW MEDIUM REMOVAL", allow_removal);
    int second = send_cdb(fd, "START STOP UNIT (eject)", eject);
    close(fd);
    return first == 0 && second == 0 ? 0 : 1;
}
