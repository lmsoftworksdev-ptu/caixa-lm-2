#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define BRIDGE_NAME "caixa.rtl.h4"
enum {
    POWER, FW_CFG, SCO_CFG, OPEN, CLOSE, LPM_TIMEOUT, LPM_MODE,
    LPM_WAKE, AUDIO_STATE, EPILOG, OFFLOAD_START, OFFLOAD_STOP
};

struct callbacks {
    size_t size;
    void (*fw)(int), (*sco)(int), (*lpm)(int), (*audio)(int);
    void *(*alloc)(int);
    void (*dealloc)(void *);
    uint8_t (*xmit)(uint16_t, void *, void (*)(void *));
    void (*epilog)(int);
};

static const struct callbacks *callbacks;
static int transport = -1;

static int vendor_init(const struct callbacks *cb, unsigned char *address)
{
    (void)address;
    if (!cb || cb->size < sizeof(*cb))
        return -1;
    callbacks = cb;
    return 0;
}

static void vendor_close(void)
{
    if (transport >= 0) {
        close(transport);
        transport = -1;
    }
}

static int vendor_op(int op, void *parameter)
{
    switch (op) {
    case OPEN: {
        const struct sockaddr_un address = {.sun_family = AF_UNIX, .sun_path = "\0" BRIDGE_NAME};
        vendor_close();
        transport = socket(AF_UNIX, SOCK_STREAM, 0);
        if (transport < 0 || connect(transport, (const struct sockaddr *)&address,
                                     offsetof(struct sockaddr_un, sun_path) + sizeof(BRIDGE_NAME)) < 0) {
            vendor_close();
            return -1;
        }
        ((int *)parameter)[0] = transport;
        return 1;
    }
    case CLOSE: vendor_close(); return 0;
    case FW_CFG: callbacks->fw(0); return 0;
    case SCO_CFG: if (callbacks->sco) callbacks->sco(0); return 0;
    case LPM_TIMEOUT: *(uint32_t *)parameter = 0; return 0;
    case LPM_MODE: callbacks->lpm(0); return 0;
    case EPILOG: callbacks->epilog(0); return 0;
    case POWER:
    case LPM_WAKE:
    case AUDIO_STATE: return 0;
    default: return -1;
    }
}

static void vendor_ssr(int reason)
{
    (void)reason;
    vendor_close();
}

const struct {
    size_t size;
    int (*init)(const struct callbacks *, unsigned char *);
    int (*op)(int, void *);
    void (*cleanup)(void);
    void (*ssr_cleanup)(int);
} BLUETOOTH_VENDOR_LIB_INTERFACE = {
    sizeof(BLUETOOTH_VENDOR_LIB_INTERFACE),
    vendor_init, vendor_op, vendor_close, vendor_ssr
};
