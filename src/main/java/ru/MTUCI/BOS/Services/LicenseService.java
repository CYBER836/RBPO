package ru.MTUCI.BOS.Services;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.MTUCI.BOS.Requests.*;
import ru.MTUCI.BOS.Requests.LicenseRequest;
import ru.MTUCI.BOS.Repositories.DeviceRepository;
import ru.MTUCI.BOS.Repositories.LicenseRepository;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;



@Service
@RequiredArgsConstructor
public class LicenseService{

    private final LicenseRepository licenseRepository;
    private final ProductService productService;
    private final UserService userService;
    private final LicenseTypeService licenseTypeService;
    private final LicenseHistoryService licenseHistoryService;
    private final DeviceLicenseService deviceLicenseService;
    private final DeviceRepository deviceRepository;

    public License createLicense(LicenseRequest licenseRequest) {

        Product product = productService.getProductById(licenseRequest.getProductId());
        if(product == null){
            throw new IllegalArgumentException("Product not found");
        }

        User user = userService.getUserById(licenseRequest.getUserId());
        if(user == null){
            throw new IllegalArgumentException("User not found");
        }

        LicenseType licenseType = licenseTypeService.getLicenseTypeById(licenseRequest.getLicenseTypeId());
        if(licenseType == null){
            throw new IllegalArgumentException("License type not found");
        }

        // Генерация кода sha256
        String code = generateLicenseCode(licenseRequest);

        // Создание лицензии
        License license = new License();
        license.setCode(code);
        license.setUser(null); //
        license.setProduct(product);
        license.setType(licenseType);
        license.setFirstActivationDate(null);
        license.setEndingDate(null);
        license.setIsBlocked(false);
        license.setDevicesCount(licenseRequest.getDeviceCount());
        license.setOwner(user); // Владелец лицензии
        license.setDuration(licenseRequest.getDuration());
        license.setDescription(licenseRequest.getDescription());
        license.setProduct(product);
        licenseRepository.save(license);

        // Сохранить историю
        LicenseHistory licenseHistory = new LicenseHistory(license, user, "CREATED", new Date(), "License created");
        licenseHistoryService.saveLicenseHistory(licenseHistory);

        return license;
    }

    public Ticket activateLicense(String activationCode, Device device, String login) {

        License license = licenseRepository.getLicensesByCode(activationCode);
        if(license == null){
            throw new IllegalArgumentException("Лицензия не найдена");
        }

        User user = userService.findUserByLogin(login);
        if(user == null){
            throw new IllegalArgumentException("Пользователь не найден");
        }

        // Если валидно
        validateActivation(license, device, login);

        // То обновляем
        if(license.getFirstActivationDate() == null){
            updateLicenseForActivation(license, user);
        }

        // То создать связь
        createDeviceLicense(license, device);

        // История
        LicenseHistory licenseHistory = new LicenseHistory(license, license.getOwner(), "ACTIVATED", new Date(), "License activated");
        licenseHistoryService.saveLicenseHistory(licenseHistory);

        // Тикет
        return generateTicket(license, device);
    }

    public License getActiveLicenseForDevice(Device device, User user, String code) {
        License license = licenseRepository.getLicensesByCode(code);

        if(license == null){
            throw new IllegalArgumentException("License not found");
        }

        DeviceLicense deviceLicense = deviceLicenseService.getDeviceLicenseByDeviceIdAndLicenseId(device.getId(), license.getId());

        if(deviceLicense == null){
            throw new IllegalArgumentException("License for this device not found");
        }

        if (license.getIsBlocked()){
            throw new IllegalArgumentException("License is blocked");
        }

        return license;
    }

    public Ticket updateExistentLicense(String licenseCode, String login, String macAddress) {

        License license = licenseRepository.getLicensesByCode(licenseCode);
        if (license == null) {
            throw new IllegalArgumentException("License not found");
        }

        // Проверка на заблокированность
        if (license.getIsBlocked()) {
            throw new IllegalArgumentException("Лицензия заблокирована");
        }

        // Проверка на активацию
        if (license.getFirstActivationDate() == null) {
            throw new IllegalArgumentException("Лицензия не активирована");
        }

        // Проверка остатка времени до конца лицензии
        long currentTimeMillis = System.currentTimeMillis();
        long remainingTimeMillis = license.getEndingDate().getTime() - currentTimeMillis;

        if (remainingTimeMillis > 1 * 60 * 60 * 1000) { // 1 час в миллисекундах
            throw new IllegalArgumentException("Лицензию нельзя обновить: до истечения срока лицензии осталось более 1 часа");
        }

        // Обновление срока действия лицензии
        license.setEndingDate(new Date(license.getEndingDate().getTime() + license.getDuration()));
        licenseRepository.save(license);

        // История
        LicenseHistory licenseHistory = new LicenseHistory(license, license.getOwner(), "UPDATED", new Date(), "License updated");
        licenseHistoryService.saveLicenseHistory(licenseHistory);

        // Тикет
        return generateTicket(license, deviceRepository.findDeviceByMacAddress(macAddress));
    }


    public Ticket generateTicket(License license, Device device){
        Ticket ticket = new Ticket();

        ticket.setCurrentDate(new Date());
        ticket.setLifetime(license.getDuration());
        ticket.setActivationDate(new Date(license.getFirstActivationDate().getTime()));
        ticket.setExpirationDate(new Date(license.getEndingDate().getTime()));
        ticket.setUserId(license.getOwner().getId());
        ticket.setDeviceId(device.getId());
        ticket.setIsBlocked(false);
        ticket.setSignature(generateSignature(ticket));

        return ticket;
    }

    private void validateActivation(License license, Device device, String login) {
        User user = userService.findUserByLogin(login);

        if(license.getUser() != null){ // И теперь спереть лицуху не получится
            if(!(license.getUser().getId().equals(user.getId()))){
                throw new IllegalArgumentException("Неправильный пользователь");
            }
        }

        // Заблокирована
        if (license.getIsBlocked()) {
            throw new IllegalArgumentException("Нельзя активировать лицензию: лицензия заблокирована");
        }

        // Истекла
        if(license.getEndingDate() != null) {
            if (license.getEndingDate().before(new Date())) {
                throw new IllegalArgumentException("Нельзя активировать лицензию: лицензия истекла");
            }
        }

        // Если девайсов больше
        if (license.getDevicesCount() <= deviceLicenseService.getDeviceLicensesByLicense(license).size()) {
            throw new IllegalArgumentException("Нельзя активировать лицензию: кол-во устройств превышено");
        }
    }

    private void createDeviceLicense(License license, Device device) {
        DeviceLicense deviceLicense = new DeviceLicense();
        deviceLicense.setDevice(device);
        deviceLicense.setLicense(license);
        deviceLicense.setActivationDate(new Date());

        deviceLicenseService.save(deviceLicense);
    }

    private void updateLicenseForActivation(License license, User user) {
        license.setFirstActivationDate(new Date());
        license.setEndingDate(new Date(System.currentTimeMillis() + license.getDuration()));
        license.setUser(user);
        licenseRepository.save(license);
    }

    public String generateSignature(Ticket ticket) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = String.valueOf(ticket.getUserId() + ticket.getDeviceId() + ticket.getActivationDate().getTime() +
                    ticket.getExpirationDate().getTime() + ticket.getLifetime() + ticket.getCurrentDate().getTime());
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ошибка генерации подписи", e);
        }
    }

    private String generateLicenseCode(LicenseRequest licenseRequest){
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = licenseRequest.getProductId() + licenseRequest.getUserId() + licenseRequest.getLicenseTypeId() + licenseRequest.getDeviceCount() + licenseRequest.getDuration() + licenseRequest.getDescription() + LocalDateTime.now();
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ошибка генерации кода лицензии", e);
        }
    }
}